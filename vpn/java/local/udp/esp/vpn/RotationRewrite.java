package local.udp.esp.vpn;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Forward-thread only. Exact local actor identity; complete missing Rotation without changing gameplay state. */
final class RotationRewrite {
    private static final long CACHE_NS=30000000000L;
    private static final int MAX_CACHE=512,MAX_BYTES=2*1024*1024;
    private final LinkedHashMap<String,Flow> flows=new LinkedHashMap<>();
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> closed=new LinkedHashMap<>();
    private int cacheBytes;
    private static final class Flow {long login;int seq;boolean seen;}
    private static final class Cached {
        final String flow;final byte[] input,output;final long at=System.nanoTime();
        Cached(String flow,byte[] input,byte[] output){this.flow=flow;this.input=input.clone();this.output=output;}
        int bytes(){return input.length+(output==null?0:output.length);}
    }
    private static final class Field {int number,wire,start,tagEnd,content,end;long value;}
    private static final class Cursor {
        final byte[] data;int p;
        Cursor(byte[] data){this.data=data;}
        long varint(){
            long value=0;
            for(int shift=0;shift<70;shift+=7){
                if(p>=data.length)throw new IllegalArgumentException("truncated varint");
                int b=data[p++]&255;if(shift==63&&b>1)throw new IllegalArgumentException("varint overflow");
                value|=(long)(b&127)<<shift;if(b<128)return value;
            }throw new IllegalArgumentException("varint overflow");
        }
    }
    private static int be16(byte[] b,int p){return ((b[p]&255)<<8)|(b[p+1]&255);}
    private static int le16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
    private static String stream(String peer,int local,int remote){return local+"@"+peer+":"+remote;}
    private static void varint(ByteArrayOutputStream out,long value){
        do{int b=(int)(value&127);value>>>=7;out.write(b|(value==0?0:128));}while(value!=0);
    }
    private static List<Field> fields(byte[] data,int limit){
        Cursor c=new Cursor(data);List<Field> result=new ArrayList<>();
        while(c.p<data.length){
            if(result.size()>=limit)throw new IllegalArgumentException("field limit");
            Field f=new Field();f.start=c.p;long tag=c.varint();
            if(tag<=0||(tag>>>3)>0x1fffffffL)throw new IllegalArgumentException("invalid tag");
            f.number=(int)(tag>>>3);f.wire=(int)(tag&7);f.tagEnd=c.p;f.content=c.p;
            if(f.wire==0)f.value=c.varint();
            else if(f.wire==2){long n=c.varint();if(n<0||n>data.length-c.p)throw new IllegalArgumentException("invalid field length");f.content=c.p;c.p+=(int)n;}
            else throw new IllegalArgumentException("unverified scene wire");
            f.end=c.p;result.add(f);
        }return result;
    }
    private static void raw(ByteArrayOutputStream out,byte[] data,Field f){out.write(data,f.start,f.end-f.start);}
    private static void nested(ByteArrayOutputStream out,byte[] data,Field f,byte[] value){
        out.write(data,f.start,f.tagEnd-f.start);varint(out,value.length);out.write(value,0,value.length);
    }
    private static byte[] contents(byte[] data,Field f){return Arrays.copyOfRange(data,f.content,f.end);}
    /** Unity Euler(pitch,yaw,0), with yaw=atan2(dx,dz), pitch positive downward. */
    static long[] quaternion(NearestTarget.Choice target,long scale){
        double yaw=Math.atan2(target.directionX,target.directionZ);
        double pitch=Math.atan2(-target.directionY,Math.hypot(target.directionX,target.directionZ));
        double sy=Math.sin(yaw/2),cy=Math.cos(yaw/2),sx=Math.sin(pitch/2),cx=Math.cos(pitch/2);
        double[] q={cy*sx,sy*cx,-sy*sx,cy*cx};long[] result=new long[4];
        for(int i=0;i<4;i++)result[i]=Math.round(q[i]*scale);
        return result;
    }
    private byte[] action(byte[] data,NearestTarget.Choice target,int[] counts,boolean enabled){
        List<Field> fs=fields(data,100000);int kinds=0,scales=0;long kind=0,scale=0;
        List<Long> values=new ArrayList<>();
        for(Field f:fs){
            if(f.number==1&&f.wire==0){kind=f.value;kinds++;}
            else if(f.number==2&&f.wire==0){scale=f.value;scales++;}
            else if(f.number==3){
                if(f.wire==0)values.add(f.value);
                else if(f.wire==2){Cursor c=new Cursor(contents(data,f));while(c.p<c.data.length){if(values.size()>=32768)throw new IllegalArgumentException("values limit");values.add(c.varint());}}
                else throw new IllegalArgumentException("unverified action values");
            }else if(f.number!=4||f.wire!=2)throw new IllegalArgumentException("unverified action field");
        }
        if(kinds!=1||kind!=2)return null;counts[0]++;
        // Keep the received scale; low precision/raw integer quaternions are not rewritten.
        if(!enabled||scales!=1||scale<100||scale>0xffffffffL||values.size()<4){counts[2]++;return null;}
        long[] q=quaternion(target,scale);boolean changed=false;
        for(int i=0;i<4;i++)if(values.get(i).longValue()!=q[i])changed=true;
        if(!changed)return null;
        ByteArrayOutputStream out=new ByteArrayOutputStream(data.length+40);int index=0;
        for(Field f:fs){
            if(f.number!=3){raw(out,data,f);continue;}
            if(f.wire==0){
                if(index<4){out.write(data,f.start,f.tagEnd-f.start);varint(out,q[index]);}else raw(out,data,f);
                index++;
            }else{
                Cursor c=new Cursor(contents(data,f));ByteArrayOutputStream packed=new ByteArrayOutputStream(f.end-f.content+40);
                while(c.p<c.data.length){int start=c.p;c.varint();if(index<4)varint(packed,q[index]);else packed.write(c.data,start,c.p-start);index++;}
                nested(out,data,f,packed.toByteArray());
            }
        }
        counts[1]++;return out.toByteArray();
    }
    private static byte[] missingRotation(NearestTarget.Choice target){
        // Normal camera consumer only updates yaw. Do not tilt the actor to force pitch.
        final long scale=1000000L;double halfYaw=Math.atan2(target.directionX,target.directionZ)/2;
        long[] q={0,Math.round(Math.sin(halfYaw)*scale),0,Math.round(Math.cos(halfYaw)*scale)};
        ByteArrayOutputStream action=new ByteArrayOutputStream(64);
        varint(action,8);varint(action,2);varint(action,16);varint(action,scale);
        ByteArrayOutputStream values=new ByteArrayOutputStream(40);for(long v:q)varint(values,v);
        byte[] packed=values.toByteArray();varint(action,26);varint(action,packed.length);action.write(packed,0,packed.length);
        return action.toByteArray();
    }
    private byte[] entity(byte[] data,NearestTarget.Choice target,int[] counts,boolean enabled){
        List<Field> fs=fields(data,100000);long id=0,id2=0,category=0;int ids=0,ids2=0,categories=0;
        for(Field f:fs){
            if(f.number==1&&f.wire==0){id=f.value;ids++;}
            else if(f.number==2&&f.wire==0){id2=f.value;ids2++;}
            else if(f.number==3&&f.wire==0){category=f.value;categories++;}
            else if(f.number!=4||f.wire!=2)throw new IllegalArgumentException("unverified entity field");
        }
        if(ids!=1||ids2>1||categories!=1||category!=4||!Long.toString(id).equals(target.ownEntityId))return null;
        if(!Long.toString(id2).equals(target.ownId2)){counts[4]++;return null;}
        counts[3]++;
        ByteArrayOutputStream out=new ByteArrayOutputStream(data.length+128);boolean changed=false;
        int beforeRotation=counts[0],actionCount=0;
        for(Field f:fs){
            if(f.number==4)actionCount++;
            byte[] value=f.number==4?action(contents(data,f),target,counts,enabled):null;
            if(value==null)raw(out,data,f);else{nested(out,data,f,value);changed=true;}
        }
        // Only supplement an existing, strictly identified local actor update.
        // Existing Rotation (including unsupported scale) is never duplicated or replaced here.
        if(enabled&&actionCount>0&&counts[0]==beforeRotation){
            byte[] added=missingRotation(target);varint(out,34);varint(out,added.length);out.write(added,0,added.length);
            counts[5]++;changed=true;
        }
        return changed?out.toByteArray():null;
    }
    private byte[] scene(byte[] data,NearestTarget.Choice target,int[] counts,boolean enabled){
        List<Field> fs=fields(data,100000);ByteArrayOutputStream out=new ByteArrayOutputStream(data.length+64);boolean changed=false;
        for(Field f:fs){
            if(!((f.number==4||f.number==5)&&f.wire==0)&&!(f.number==7&&(f.wire==0||f.wire==2))&&!(f.number==6&&f.wire==2))throw new IllegalArgumentException("unverified scene field");
            byte[] value=f.number==6?entity(contents(data,f),target,counts,enabled):null;
            if(value==null)raw(out,data,f);else{nested(out,data,f,value);changed=true;}
        }return changed?out.toByteArray():null;
    }
    private void trim(){
        long now=System.nanoTime();Iterator<Cached> it=cache.values().iterator();
        while(it.hasNext()){Cached c=it.next();if(now-c.at>CACHE_NS){cacheBytes-=c.bytes();it.remove();}}
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
    byte[] incoming(String peer,int local,int remote,byte[] body,double timestamp){
        try{
            if(body==null||body.length<14||body.length>65507)return null;
            int flag=body[1]&255,kind=flag&15;
            if((kind!=4&&kind!=5)||(flag&96)!=0||(kind==4&&(flag&16)!=0))return null;
            if((body[0]&255)!=((body.length+flag+(body[2]&255)+(body[3]&255))&255))return null;
            byte[] app=Arrays.copyOfRange(body,10,body.length);if((flag&128)!=0)app=VpnNative.decompress(app);
            if(app==null||app.length<4||app.length>GameDecoder.MAX_MESSAGE||be16(app,0)!=3||be16(app,2)!=852)return null;
            String stream=stream(peer,local,remote),flowKey=stream+"/"+kind;NearestTarget.Table table=LiveRuntime.targets;
            long login=Double.doubleToLongBits(table.login(stream));Long blocked=closed.get(stream);
            if(blocked!=null){if(blocked.longValue()==login)return null;closed.remove(stream);}
            Flow flow=flows.get(flowKey);if(flow==null||flow.login!=login){clearFlow(flowKey);flow=new Flow();flow.login=login;flows.put(flowKey,flow);}
            while(flows.size()>32)clearFlow(flows.keySet().iterator().next());
            trim();int seq=le16(body,2);String key=flowKey+"/"+seq;Cached prior=cache.get(key);
            if(prior!=null&&Arrays.equals(prior.input,body))return prior.output;
            if(flow.seen&&((seq-flow.seq)&65535)>=32768||flow.seen&&seq==flow.seq)return null;
            flow.seen=true;flow.seq=seq;LiveRuntime.rotationScenes++;
            LiveRuntime.ViewControl control=LiveRuntime.viewControl();NearestTarget.Choice target=table.get(stream);byte[] result=null;
            if(target==null)LiveRuntime.rotationStatus=table.reason(stream);
            else{
                int[] counts=new int[6];byte[] payload=scene(Arrays.copyOfRange(app,4,app.length),target,counts,control.enabled);
                LiveRuntime.rotationMatches+=counts[0];
                if(control.enabled&&payload!=null){
                    byte[] changed=new byte[4+payload.length];System.arraycopy(app,0,changed,0,4);System.arraycopy(payload,0,changed,4,payload.length);
                    if((flag&128)!=0)changed=VpnNative.compress(changed);
                    if(changed==null||changed.length+10>65507)throw new IllegalArgumentException("rewritten packet too large");
                    result=Arrays.copyOf(body,10+changed.length);System.arraycopy(changed,0,result,10,changed.length);
                    result[0]=(byte)((result.length+(result[1]&255)+(result[2]&255)+(result[3]&255))&255);
                    if(control!=LiveRuntime.viewControl()||System.nanoTime()>=target.expiresNs)result=null;
                    else {LiveRuntime.rotationRewrites++;LiveRuntime.rotationAdded+=counts[5];}
                }
                if(!control.enabled)LiveRuntime.rotationStatus="开关关闭，新包原样";
                else if(counts[4]>0&&counts[3]==0)LiveRuntime.rotationStatus="同id1但id2不匹配，未放宽身份";
                else if(counts[3]==0)LiveRuntime.rotationStatus="该帧没有匹配的自身实体";
                else if(counts[5]>0)LiveRuntime.rotationStatus=result!=null?"已补自身水平Rotation，镜头采用待实测":"自身Rotation补齐已取消，原样";
                else if(counts[0]==0)LiveRuntime.rotationStatus="该帧自身实体无可用动作，原样";
                else if(counts[2]>0)LiveRuntime.rotationStatus=result==null?"自身Rotation长度/scale不满足，原样":"已改部分Rotation，其余长度/scale不满足";
                else if(result!=null)LiveRuntime.rotationStatus="已改自身旋转→"+target.entityId+"，镜头取决于游戏状态";
                else LiveRuntime.rotationStatus="方向相同或目标/开关已变化";
            }
            store(key,flowKey,body,result);return result;
        }catch(Exception|LinkageError error){LiveRuntime.rotationStatus="下行透传："+error.getMessage();return null;}
    }
}
