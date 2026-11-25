/* JNI bridge: local TUN -> zdtun -> protected sockets. No remote VPN endpoint. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <errno.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include "third_party/zdtun/zdtun.h"
#include "third_party/zdtun/utils.h"
#include "third_party/zdtun/third_party/net_headers.h"
#include "third_party/zstd/lib/zstd.h"

typedef struct {
    int wake[2]; int tun_fd; atomic_int stopped;
    JNIEnv *env; jobject callbacks; jmethodID protect,packet,view,view_sent,view_forget,rotation,rotation_delivered;
    uint64_t packets;
} bridge;

static void exception_clear(JNIEnv *env) { if((*env)->ExceptionCheck(env))(*env)->ExceptionClear(env); }
static void socket_open(zdtun_t *tun,socket_t fd) {
    bridge *ctx=zdtun_userdata(tun);
    jboolean ok=(*ctx->env)->CallBooleanMethod(ctx->env,ctx->callbacks,ctx->protect,(jint)fd);
    if((*ctx->env)->ExceptionCheck(ctx->env)){exception_clear(ctx->env);ok=JNI_FALSE;}
    if(!ok){
        /* Keep the descriptor owned by zdtun, but make it unusable on failure.
           Otherwise a non-protected forward socket could recurse into the VPN. */
        atomic_store(&ctx->stopped,1);
        dup2(ctx->wake[0],fd);
    }
}
/* Return a separately owned IP packet. The original is still accounted to ESP. */
static char *rewrite_rotation(zdtun_t *tun,zdtun_pkt_t *packet,const zdtun_conn_t *conn,size_t *length) {
    bridge *ctx=zdtun_userdata(tun);JNIEnv *env=ctx->env;
    if(packet->tuple.ipproto!=IPPROTO_UDP||packet->l7_len<14||packet->l4_hdr_len!=8||
       (packet->flags&ZDTUN_PKT_IS_FRAGMENT))return NULL;
    size_t offset=(size_t)(packet->l7-packet->buf);
    if(offset+packet->l7_len!=packet->len)return NULL;
    const uint8_t *raw=(const uint8_t*)packet->l7;int flag=raw[1];
    if(((flag&15)!=4&&(flag&15)!=5)||(flag&96)||((flag&15)==4&&(flag&16)))return NULL;
    const zdtun_5tuple_t *tuple=zdtun_conn_get_5tuple(conn);char peer[INET6_ADDRSTRLEN];
    if(!inet_ntop(tuple->ipver==6?AF_INET6:AF_INET,&tuple->dst_ip,peer,sizeof(peer)))return NULL;
    jbyteArray body=(*env)->NewByteArray(env,packet->l7_len);if(!body){exception_clear(env);return NULL;}
    (*env)->SetByteArrayRegion(env,body,0,packet->l7_len,(const jbyte*)packet->l7);
    jstring host=(*env)->NewStringUTF(env,peer);
    if(!host){exception_clear(env);(*env)->DeleteLocalRef(env,body);return NULL;}
    struct timespec ts;clock_gettime(CLOCK_REALTIME,&ts);
    jbyteArray changed=(*env)->CallObjectMethod(env,ctx->callbacks,ctx->rotation,host,
        (jint)ntohs(tuple->src_port),(jint)ntohs(tuple->dst_port),body,(jdouble)ts.tv_sec+ts.tv_nsec/1e9);
    (*env)->DeleteLocalRef(env,host);(*env)->DeleteLocalRef(env,body);
    if((*env)->ExceptionCheck(env)){exception_clear(env);if(changed)(*env)->DeleteLocalRef(env,changed);return NULL;}
    if(!changed)return NULL;
    jsize n=(*env)->GetArrayLength(env,changed);char *result=NULL;
    if(n>=14&&n<=65507&&offset+(size_t)n<=65535){
        size_t total=offset+(size_t)n;result=malloc(total);
        if(result){
            memcpy(result,packet->buf,offset);
            (*env)->GetByteArrayRegion(env,changed,0,n,(jbyte*)(result+offset));
            if((*env)->ExceptionCheck(env)){exception_clear(env);free(result);result=NULL;}
            else {
                struct udphdr *udp=(struct udphdr*)(result+(packet->l4-packet->buf));
                udp->uh_ulen=htons(8+n);udp->uh_sum=0;
                if(tuple->ipver==4){
                    struct iphdr *ip=(struct iphdr*)result;ip->tot_len=htons(total);ip->check=0;
                    ip->check=~calc_checksum(0,(const uint8_t*)result,packet->ip_hdr_len);
                }else ((struct ipv6_hdr*)result)->payload_len=htons(total-40);
                udp->uh_sum=zdtun_l3_checksum(tun,(zdtun_conn_t*)conn,result,(char*)udp,8+n);
                *length=total;
            }
        }
    }
    (*env)->DeleteLocalRef(env,changed);return result;
}
static int send_client(zdtun_t *tun,zdtun_pkt_t *packet,const zdtun_conn_t *conn) {
    bridge *ctx=zdtun_userdata(tun);
    if(atomic_load(&ctx->stopped))return -1;
    size_t length=packet->len;char *changed=rewrite_rotation(tun,packet,conn,&length);
    const char *data=changed?changed:packet->buf;ssize_t n;
    do { n=write(ctx->tun_fd,data,length); } while(n<0&&errno==EINTR);
    int success=n==(ssize_t)length;
    if(changed){
        JNIEnv *env=ctx->env;
        (*env)->CallVoidMethod(env,ctx->callbacks,ctx->rotation_delivered,success?JNI_TRUE:JNI_FALSE);
        exception_clear(env);
    }
    free(changed);return success?0:-1;
}
/* Outgoing view vector path; incoming Rotation has a separate path. */
static char *rewrite_view(zdtun_t *tun,const zdtun_pkt_t *packet,const zdtun_conn_t *conn,uint16_t *length) {
    bridge *ctx=zdtun_userdata(tun);JNIEnv *env=ctx->env;
    if(atomic_load(&ctx->stopped)||packet->tuple.ipproto!=IPPROTO_UDP||packet->l7_len<18||
       (packet->flags&ZDTUN_PKT_IS_FRAGMENT))return NULL;
    const uint8_t *raw=(const uint8_t*)packet->l7;int flag=raw[1];
    if(((flag&15)!=4&&(flag&15)!=5)||(flag&96)||((flag&15)==4&&(flag&16)))return NULL;
    const zdtun_5tuple_t *tuple=zdtun_conn_get_5tuple(conn);char peer[INET6_ADDRSTRLEN];
    if(!inet_ntop(tuple->ipver==6?AF_INET6:AF_INET,&tuple->dst_ip,peer,sizeof(peer)))return NULL;
    jbyteArray body=(*env)->NewByteArray(env,packet->l7_len);if(!body){exception_clear(env);return NULL;}
    (*env)->SetByteArrayRegion(env,body,0,packet->l7_len,(const jbyte*)raw);
    jstring host=(*env)->NewStringUTF(env,peer);
    if(!host){exception_clear(env);(*env)->DeleteLocalRef(env,body);return NULL;}
    struct timespec ts;clock_gettime(CLOCK_REALTIME,&ts);
    jbyteArray changed=(*env)->CallObjectMethod(env,ctx->callbacks,ctx->view,host,
        (jint)ntohs(tuple->src_port),(jint)ntohs(tuple->dst_port),body,(jdouble)ts.tv_sec+ts.tv_nsec/1e9);
    (*env)->DeleteLocalRef(env,host);(*env)->DeleteLocalRef(env,body);
    if((*env)->ExceptionCheck(env)){exception_clear(env);if(changed)(*env)->DeleteLocalRef(env,changed);return NULL;}
    if(!changed)return NULL;
    jsize n=(*env)->GetArrayLength(env,changed);char *result=NULL;
    if(n>=18&&n<=65507){
        result=malloc((size_t)n);
        if(result){
            (*env)->GetByteArrayRegion(env,changed,0,n,(jbyte*)result);
            if((*env)->ExceptionCheck(env)){exception_clear(env);free(result);result=NULL;}
            else *length=(uint16_t)n;
        }
    }
    (*env)->DeleteLocalRef(env,changed);return result;
}
static void view_sent(zdtun_t *tun,int success){
    bridge *ctx=zdtun_userdata(tun);JNIEnv *env=ctx->env;
    (*env)->CallVoidMethod(env,ctx->callbacks,ctx->view_sent,success?JNI_TRUE:JNI_FALSE);exception_clear(env);
}
static void view_closed(zdtun_t *tun,const zdtun_conn_t *conn){
    bridge *ctx=zdtun_userdata(tun);const zdtun_5tuple_t *tuple=zdtun_conn_get_5tuple(conn);
    if(tuple->ipproto!=IPPROTO_UDP)return;char peer[INET6_ADDRSTRLEN];
    if(!inet_ntop(tuple->ipver==6?AF_INET6:AF_INET,&tuple->dst_ip,peer,sizeof(peer)))return;
    JNIEnv *env=ctx->env;jstring host=(*env)->NewStringUTF(env,peer);
    if(host){(*env)->CallVoidMethod(env,ctx->callbacks,ctx->view_forget,host,
        (jint)ntohs(tuple->src_port),(jint)ntohs(tuple->dst_port));(*env)->DeleteLocalRef(env,host);}
    exception_clear(env);
}
static void account(zdtun_t *tun,const zdtun_pkt_t *pkt,uint8_t outgoing,const zdtun_conn_t *conn) {
    bridge *ctx=zdtun_userdata(tun);
    if(pkt->tuple.ipproto!=IPPROTO_UDP||pkt->l7_len<2||pkt->l7_len>65507)return;
    const zdtun_5tuple_t *tuple=zdtun_conn_get_5tuple(conn);
    char peer[INET6_ADDRSTRLEN];
    if(!inet_ntop(tuple->ipver==6?AF_INET6:AF_INET,&tuple->dst_ip,peer,sizeof(peer)))return;
    JNIEnv *env=ctx->env;
    jbyteArray body=(*env)->NewByteArray(env,pkt->l7_len);
    if(!body){exception_clear(env);return;}
    (*env)->SetByteArrayRegion(env,body,0,pkt->l7_len,(const jbyte*)pkt->l7);
    jstring host=(*env)->NewStringUTF(env,peer);
    if(!host){exception_clear(env);(*env)->DeleteLocalRef(env,body);return;}
    struct timespec ts;clock_gettime(CLOCK_REALTIME,&ts);
    jdouble now=(jdouble)ts.tv_sec+ts.tv_nsec/1e9;
    (*env)->CallVoidMethod(env,ctx->callbacks,ctx->packet,(jboolean)outgoing,host,
        (jint)ntohs(tuple->src_port),(jint)ntohs(tuple->dst_port),body,now);
    exception_clear(env);
    (*env)->DeleteLocalRef(env,body);(*env)->DeleteLocalRef(env,host);ctx->packets++;
}
JNIEXPORT jlong JNICALL Java_local_udp_esp_vpn_VpnNative_create(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;bridge *ctx=calloc(1,sizeof(*ctx));if(!ctx)return 0;
    if(pipe(ctx->wake)<0){free(ctx);return 0;}
    fcntl(ctx->wake[0],F_SETFL,O_NONBLOCK);fcntl(ctx->wake[1],F_SETFL,O_NONBLOCK);
    fcntl(ctx->wake[0],F_SETFD,FD_CLOEXEC);fcntl(ctx->wake[1],F_SETFD,FD_CLOEXEC);
    atomic_init(&ctx->stopped,0);return (jlong)(intptr_t)ctx;
}
JNIEXPORT void JNICALL Java_local_udp_esp_vpn_VpnNative_stop(JNIEnv *env,jclass cls,jlong handle) {
    (void)env;(void)cls;bridge *ctx=(bridge*)(intptr_t)handle;if(!ctx)return;
    atomic_store(&ctx->stopped,1);char byte=1;(void)write(ctx->wake[1],&byte,1);
}
JNIEXPORT void JNICALL Java_local_udp_esp_vpn_VpnNative_destroy(JNIEnv *env,jclass cls,jlong handle) {
    (void)env;(void)cls;bridge *ctx=(bridge*)(intptr_t)handle;if(!ctx)return;
    close(ctx->wake[0]);close(ctx->wake[1]);free(ctx);
}
JNIEXPORT jint JNICALL Java_local_udp_esp_vpn_VpnNative_run(JNIEnv *env,jclass cls,jlong handle,jint fd,jobject callbacks) {
    (void)cls;bridge *ctx=(bridge*)(intptr_t)handle;
    if(!ctx||fd<0||fd>=FD_SETSIZE||ctx->wake[0]>=FD_SETSIZE)return -1;
    ctx->env=env;ctx->callbacks=callbacks;ctx->tun_fd=fd;
    jclass type=(*env)->GetObjectClass(env,callbacks);
    ctx->protect=(*env)->GetMethodID(env,type,"protectForwardSocket","(I)Z");
    ctx->packet=(*env)->GetMethodID(env,type,"onUdp","(ZLjava/lang/String;II[BD)V");
    ctx->view=(*env)->GetMethodID(env,type,"rewriteViewOutgoing","(Ljava/lang/String;II[BD)[B");
    ctx->view_sent=(*env)->GetMethodID(env,type,"onViewSent","(Z)V");
    ctx->view_forget=(*env)->GetMethodID(env,type,"forgetViewFlow","(Ljava/lang/String;II)V");
    ctx->rotation=(*env)->GetMethodID(env,type,"rewriteRotationIncoming","(Ljava/lang/String;II[BD)[B");
    ctx->rotation_delivered=(*env)->GetMethodID(env,type,"onRotationDelivered","(Z)V");
    (*env)->DeleteLocalRef(env,type);
    if(!ctx->protect||!ctx->packet||!ctx->view||!ctx->view_sent||!ctx->view_forget||!ctx->rotation||!ctx->rotation_delivered||(*env)->ExceptionCheck(env)){exception_clear(env);return -2;}
    zdtun_callbacks_t cb={0};cb.send_client=send_client;cb.account_packet=account;cb.on_socket_open=socket_open;
    cb.rewrite_udp=rewrite_view;cb.on_udp_rewrite_sent=view_sent;cb.on_connection_close=view_closed;
    zdtun_t *tun=zdtun_init(&cb,ctx);if(!tun)return -3;
    zdtun_set_mtu(tun,1500);fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_NONBLOCK);
    char *buffer=malloc(65535);if(!buffer){zdtun_finalize(tun);return -4;}
    time_t last_purge=0;int result=0;
    while(!atomic_load(&ctx->stopped)) {
        fd_set rd,wr;int maxfd;zdtun_fds(tun,&maxfd,&rd,&wr);
        FD_SET(fd,&rd);FD_SET(ctx->wake[0],&rd);
        if(fd>maxfd)maxfd=fd;if(ctx->wake[0]>maxfd)maxfd=ctx->wake[0];
        struct timeval timeout={.tv_sec=0,.tv_usec=250000};
        int ready=select(maxfd+1,&rd,&wr,NULL,&timeout);
        if(ready<0){if(errno==EINTR)continue;result=-5;break;}
        if(atomic_load(&ctx->stopped)||FD_ISSET(ctx->wake[0],&rd))break;
        if(ready>0){
            if(FD_ISSET(fd,&rd)) {
                /* Bound each drain to keep reverse traffic and Stop responsive. */
                for(int i=0;i<64&&!atomic_load(&ctx->stopped);i++){
                    ssize_t n=read(fd,buffer,65535);
                    if(n<0){if(errno==EINTR){i--;continue;}if(errno==EAGAIN||errno==EWOULDBLOCK)break;result=-6;atomic_store(&ctx->stopped,1);break;}
                    if(n==0){result=-7;atomic_store(&ctx->stopped,1);break;}
                    zdtun_easy_forward(tun,buffer,(int)n);
                }
            }
            FD_CLR(fd,&rd);FD_CLR(ctx->wake[0],&rd);
            zdtun_handle_fd(tun,&rd,&wr);
        }
        time_t now=time(NULL);if(now!=last_purge){zdtun_purge_expired(tun);last_purge=now;}
    }
    free(buffer);zdtun_finalize(tun);return result;
}
JNIEXPORT jbyteArray JNICALL Java_local_udp_esp_vpn_VpnNative_decompress(JNIEnv *env,jclass cls,jbyteArray compressed) {
    (void)cls;jsize input_size=(*env)->GetArrayLength(env,compressed);
    jbyte *input=(*env)->GetByteArrayElements(env,compressed,NULL);if(!input)return NULL;
    unsigned long long size=ZSTD_getFrameContentSize(input,input_size);
    if(size==0||size>8*1024*1024){(*env)->ReleaseByteArrayElements(env,compressed,input,JNI_ABORT);goto fail;}
    void *output=malloc((size_t)size);if(!output){(*env)->ReleaseByteArrayElements(env,compressed,input,JNI_ABORT);goto fail;}
    size_t n=ZSTD_decompress(output,(size_t)size,input,input_size);
    (*env)->ReleaseByteArrayElements(env,compressed,input,JNI_ABORT);
    if(ZSTD_isError(n)||n!=size){free(output);goto fail;}
    jbyteArray result=(*env)->NewByteArray(env,(jsize)n);
    if(result)(*env)->SetByteArrayRegion(env,result,0,(jsize)n,output);free(output);return result;
fail:
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalArgumentException"),"invalid or excessive zstd frame");return NULL;
}
JNIEXPORT jbyteArray JNICALL Java_local_udp_esp_vpn_VpnNative_compress(JNIEnv *env,jclass cls,jbyteArray plain) {
    (void)cls;jsize input_size=(*env)->GetArrayLength(env,plain);
    if(input_size<=0||input_size>8*1024*1024)goto fail_view_compress;
    size_t bound=ZSTD_compressBound((size_t)input_size);void *output=malloc(bound);
    if(!output)goto fail_view_compress;
    jbyte *input=(*env)->GetByteArrayElements(env,plain,NULL);if(!input){free(output);return NULL;}
    size_t n=ZSTD_compress(output,bound,input,input_size,1);
    (*env)->ReleaseByteArrayElements(env,plain,input,JNI_ABORT);
    if(ZSTD_isError(n)){free(output);goto fail_view_compress;}
    jbyteArray result=(*env)->NewByteArray(env,(jsize)n);
    if(result)(*env)->SetByteArrayRegion(env,result,0,(jsize)n,output);free(output);return result;
fail_view_compress:
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalArgumentException"),"view zstd compression failed");return NULL;
}
