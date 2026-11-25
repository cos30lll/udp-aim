package local.udp.esp.vpn;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Forward-thread only: replace outbound 3/851 field3; original data stays owned by ESP. */
final class ViewRewrite {
    private static final long CACHE_NS=30000000000L;
    private static final int MAX_CACHE=512,MAX_BYTES=2*1024*1024;
    private final LinkedHashMap<String,Flow> flows=new LinkedHashMap<>();
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> closed=new LinkedHashMap<>();
    private int cacheBytes;
    private static final class Flow {long login;int seq;boolean seen;}
    private static final class Cached {
        final String flow;final byte[] input,output;final long at;
        Cached(String flow,byte[] input,byte[] output){this.flow=flow;this.input=input.clone();this.output=output;at=System.nanoTime();}
        int bytes(){return input.length+(output==null?0:output.length);}
    }
    private static final class Field {int number,wire,start,tagEnd,content,end;}
    private static final class Cursor {
        final byte[] data;int p;
        Cursor(byte[] data){this.data=data;}
        long varint(){
            long v=0;
            for(int shift=0;shift<70;shift+=7){
                if(p>=data.length)throw new IllegalArgumentException("truncated varint");
                int b=data[p++]&255;if(shift==63&&b>1)throw new IllegalArgumentException("varint overflow");
                v|=(long)(b&127)<<shift;if(b<128)return v;
            }throw new IllegalArgumentException("varint overflow");
        }
    }
    private static int be16(byte[] b,int p){return ((b[p]&255)<<8)|(b[p+1]&255);}
    private static int le16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
    private static String stream(String peer,int local,int remote){return local+"@"+peer+":"+remote;}
    private static void varint(ByteArrayOutputStream out,long value){do{int b=(int)(value&127);value>>>=7;out.write(b|(value==0?0:128));}while(value!=0);}
    private static byte[] direction(NearestTarget.Choice target){
        ByteArrayOutputStream out=new ByteArrayOutputStream(12);int[] values={target.x,target.y,target.z};
        for(int i=0;i<3;i++){out.write(((i+1)<<3)|5);long v=values[i];varint(out,(v<<1)^(v>>63));}return out.toByteArray();
    }
    private static List<Field> fields(byte[] data){
        Cursor r=new Cursor(data);List<Field> out=new ArrayList<>();
        while(r.p<data.length){
            if(out.size()>=64)throw new IllegalArgumentException("too many move fields");
            Field f=new Field();f.start=r.p;long tag=r.varint();f.number=(int)(tag>>>3);f.wire=(int)(tag&7);f.tagEnd=r.p;f.content=r.p;
            if(f.number==2&&f.wire==0)r.varint();
            else if((f.number==1||f.number==3||f.number==4||f.number==5)&&f.wire==2){long n=r.varint();if(n<0||n>data.length-r.p)throw new IllegalArgumentException("invalid vector length");f.content=r.p;r.p+=(int)n;}
            else throw new IllegalArgumentException("unverified move field");
            f.end=r.p;out.add(f);
        }return out;
    }
    private static void validateVector(byte[] data,int start,int end){
        Cursor r=new Cursor(Arrays.copyOfRange(data,start,end));int count=0;
        while(r.p<r.data.length){long tag=r.varint();if(++count>32||(tag&7)!=5||(tag>>>3)<1||(tag>>>3)>3)throw new IllegalArgumentException("invalid view vector");long v=r.varint();if(v<0||v>0xffffffffL)throw new IllegalArgumentException("view component overflow");}
    }
    private void trim(){
        long now=System.nanoTime();Iterator<Map.Entry<String,Cached>> it=cache.entrySet().iterator();
        while(it.hasNext()){Cached c=it.next().getValue();if(now-c.at>CACHE_NS){cacheBytes-=c.bytes();it.remove();}}
        while(cache.size()>MAX_CACHE||cacheBytes>MAX_BYTES){String first=cache.keySet().iterator().next();cacheBytes-=cache.remove(first).bytes();}
    }
    private void clearFlow(String flow){
        flows.remove(flow);Iterator<Cached> it=cache.values().iterator();while(it.hasNext()){Cached c=it.next();if(flow.equals(c.flow)){cacheBytes-=c.bytes();it.remove();}}
    }
    private void store(String key,String flow,byte[] input,byte[] output){
        Cached c=new Cached(flow,input,output),old=cache.put(key,c);if(old!=null)cacheBytes-=old.bytes();cacheBytes+=c.bytes();trim();
    }
    void forget(String peer,int local,int remote){
        String stream=stream(peer,local,remote);clearFlow(stream+"/4");clearFlow(stream+"/5");
        closed.put(stream,Double.doubleToLongBits(LiveRuntime.targets.login(stream)));
        while(closed.size()>32)closed.remove(closed.keySet().iterator().next());
    }
    byte[] outgoing(String peer,int local,int remote,byte[] body,double timestamp){
        try{
            if(body==null||body.length<18||body.length>65507)return null;
            int flag=body[1]&255,kind=flag&15;
            if((kind!=4&&kind!=5)||(flag&96)!=0||(kind==4&&(flag&16)!=0))return null;
            if((body[0]&255)!=((body.length+(body[1]&255)+(body[2]&255)+(body[3]&255))&255))return null;
            byte[] app=Arrays.copyOfRange(body,14,body.length);if((flag&128)!=0)app=VpnNative.decompress(app);
            if(app==null||app.length<4||be16(app,0)!=3||be16(app,2)!=851)return null;
            String stream=stream(peer,local,remote),flowKey=stream+"/"+kind;
            NearestTarget.Table table=LiveRuntime.targets;long login=Double.doubleToLongBits(table.login(stream));
            Long blocked=closed.get(stream);if(blocked!=null){if(blocked==login){LiveRuntime.viewStatus="连接已关闭，等待重新登录";return null;}closed.remove(stream);}
            Flow flow=flows.get(flowKey);
            if(flow==null||flow.login!=login){clearFlow(flowKey);flow=new Flow();flow.login=login;flows.put(flowKey,flow);}
            while(flows.size()>32)clearFlow(flows.keySet().iterator().next());
            trim();int seq=le16(body,6);String key=flowKey+"/"+seq;Cached previous=cache.get(key);
            if(previous!=null&&Arrays.equals(previous.input,body))return previous.output;
            if(flow.seen&&((seq-flow.seq)&65535)>=32768){return null;}
            if(flow.seen&&seq==flow.seq)return null;
            flow.seen=true;flow.seq=seq;
            LiveRuntime.ViewControl control=LiveRuntime.viewControl();byte[] result=null;
            if(control.enabled){
                NearestTarget.Choice target=table.get(stream);
                if(target==null)LiveRuntime.viewStatus=table.reason(stream);
                else{
                    byte[] payload=Arrays.copyOfRange(app,4,app.length);List<Field> fields=fields(payload);boolean found=false;
                    for(Field f:fields)if(f.number==3){validateVector(payload,f.content,f.end);found=true;}
                    if(!found)LiveRuntime.viewStatus="上行缺少 field3，保持原样";
                    else{
                        byte[] vector=direction(target);ByteArrayOutputStream out=new ByteArrayOutputStream(payload.length+16);
                        for(Field f:fields){
                            if(f.number!=3)out.write(payload,f.start,f.end-f.start);
                            else{out.write(payload,f.start,f.tagEnd-f.start);varint(out,vector.length);out.write(vector,0,vector.length);}
                        }
                        byte[] changed=out.toByteArray();
                        if(!Arrays.equals(payload,changed)){
                            byte[] newApp=new byte[4+changed.length];System.arraycopy(app,0,newApp,0,4);System.arraycopy(changed,0,newApp,4,changed.length);
                            if((flag&128)!=0)newApp=VpnNative.compress(newApp);
                            if(newApp==null||newApp.length+14>65507)throw new IllegalArgumentException("rewritten packet too large");
                            result=Arrays.copyOf(body,14+newApp.length);System.arraycopy(newApp,0,result,14,newApp.length);
                            result[0]=(byte)((result.length+(result[1]&255)+(result[2]&255)+(result[3]&255))&255);
                        }
                        if(control!=LiveRuntime.viewControl()||System.nanoTime()>=target.expiresNs)result=null;
                        else{
                            if(result!=null)LiveRuntime.viewRewrites++;
                            LiveRuntime.viewStatus=String.format(Locale.ROOT,"%s [ID %s] · %.1f单位 · yaw %.1f / pitch %.1f",target.name,target.entityId,target.distance,target.yaw,target.pitch);
                        }
                    }
                }
            }
            store(key,flowKey,body,result);return result;
        }catch(Exception|LinkageError error){LiveRuntime.viewStatus="朝向改写透传："+error.getMessage();return null;}
    }
}