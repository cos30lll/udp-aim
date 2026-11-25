package local.udp.esp.vpn;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable nearest-player choices derived only from the original decoded server state. */
final class NearestTarget {
    static final double SCENE_MAX_AGE=1.5, POSITION_MAX_AGE=300, FUTURE_SKEW=0.35;
    static final class Choice {
        final String stream,entityId,key,name;
        final String ownEntityId,ownId2,ownKey;
        final int x,y,z;
        final double distance,yaw,pitch,sceneTimestamp,loginTimestamp;
        final double directionX,directionY,directionZ;
        final long expiresNs;
        Choice(String stream,JSONObject own,JSONObject target,double dx,double dy,double dz,double distance,double scene,double login,double now){
            ownEntityId=own.optString("id1");ownId2=own.optString("id2");ownKey=own.optString("key");
            directionX=dx/distance;directionY=dy/distance;directionZ=dz/distance;
            expiresNs=System.nanoTime()+(long)(Math.max(0,scene+SCENE_MAX_AGE-now)*1000000000.0);
            this.stream=stream;entityId=target.optString("id1");key=target.optString("key");
            String label=target.optString("name","");if(label.isEmpty())label="玩家 "+entityId;
            label=label.replaceAll("[\\p{Cc}\\p{Cf}]"," ");name=label.length()>32?label.substring(0,32):label;
            this.distance=distance;sceneTimestamp=scene;loginTimestamp=login;
            x=quantize(dx/distance);y=quantize(dy/distance);z=quantize(dz/distance);
            yaw=(Math.toDegrees(Math.atan2(x,z))+360)%360;
            pitch=Math.toDegrees(Math.atan2(-y,Math.hypot(x,z)));
        }
    }
    static final class Table {
        static final Table EMPTY=new Table(Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap());
        final Map<String,Choice> choices;
        final Map<String,String> reasons;
        final Map<String,Double> logins;
        final long publishedNs;
        Table(Map<String,Choice> choices,Map<String,String> reasons,Map<String,Double> logins){
            this.choices=Collections.unmodifiableMap(new LinkedHashMap<>(choices));
            this.reasons=Collections.unmodifiableMap(new LinkedHashMap<>(reasons));
            this.logins=Collections.unmodifiableMap(new LinkedHashMap<>(logins));publishedNs=System.nanoTime();
        }
        boolean fresh(){return System.nanoTime()-publishedNs<=1500000000L;}
        Choice get(String stream){Choice c=fresh()?choices.get(stream):null;return c!=null&&System.nanoTime()<c.expiresNs?c:null;}
        double login(String stream){Double t=logins.get(stream);return t==null?Double.NaN:t;}
        String reason(String stream){return !fresh()||choices.containsKey(stream)&&get(stream)==null?"场景数据已超时":reasons.containsKey(stream)?reasons.get(stream):"等待同连接的登录与场景";}
    }
    private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
    private static int quantize(double value){double v=Math.abs(value)*100;int q=(int)Math.floor(v+0.5);return value<0?-q:q;}
    private static double number(JSONObject obj,String name){Object v=obj.opt(name);return v instanceof Number?((Number)v).doubleValue():Double.NaN;}
    private static boolean recent(double ts,double now,double maxAge){return finite(ts)&&ts<=now+FUTURE_SKEW&&now-ts<=maxAge;}
    private static boolean position(JSONObject e,double now,double login){
        double ts=number(e,"timestamp");return finite(number(e,"x"))&&finite(number(e,"y"))&&finite(number(e,"z"))
            &&recent(ts,now,POSITION_MAX_AGE)&&ts>=login;
    }
    static Table fromSnapshot(JSONObject state,double now){
        if(state==null||!finite(now))return Table.EMPTY;
        JSONArray entities=state.optJSONArray("entities"),contexts=state.optJSONArray("camera_context"),scenes=state.optJSONArray("scene_streams");
        if(entities==null||entities.length()>512||contexts==null||contexts.length()>32||scenes==null||scenes.length()>32)return Table.EMPTY;
        Map<String,Choice> choices=new LinkedHashMap<>();Map<String,String> reasons=new LinkedHashMap<>();Map<String,Double> logins=new LinkedHashMap<>();
        for(int c=0;c<contexts.length();c++){
            JSONObject context=contexts.optJSONObject(c);if(context==null)continue;
            String stream=context.optString("stream","");double login=number(context,"login_timestamp"),scene=Double.NaN;
            if(stream.isEmpty()||!finite(login)||login>now+FUTURE_SKEW)continue;
            logins.put(stream,login);
            for(int j=0;j<scenes.length();j++){JSONObject s=scenes.optJSONObject(j);if(s!=null&&stream.equals(s.optString("stream")))scene=number(s,"timestamp");}
            if(!recent(scene,now,SCENE_MAX_AGE)||scene<login){reasons.put(stream,"等待新鲜场景");continue;}
            JSONObject own=null;int count=0;
            for(int i=0;i<entities.length();i++){
                JSONObject e=entities.optJSONObject(i);
                if(e!=null&&e.optInt("category")==4&&stream.equals(e.optString("stream"))&&Boolean.TRUE.equals(e.opt("is_local"))&&position(e,now,login)){own=e;count++;}
            }
            if(count!=1){reasons.put(stream,count>1?"自身实体不唯一":"等待已确认自身坐标");continue;}
            JSONObject best=null;double bestSquared=Double.POSITIVE_INFINITY;
            for(int i=0;i<entities.length();i++){
                JSONObject e=entities.optJSONObject(i);
                if(e==null||e.optInt("category")!=4||!stream.equals(e.optString("stream"))
                    ||!Boolean.TRUE.equals(e.opt("is_player"))||!Boolean.FALSE.equals(e.opt("is_local"))||!position(e,now,login))continue;
                if(own.optString("id1").equals(e.optString("id1"))||own.optString("key").equals(e.optString("key")))continue;
                String uid=e.optString("player_uid","");if(uid.isEmpty()||"0".equals(uid)||uid.equals(own.optString("player_uid","")))continue;
                double dx=number(e,"x")-number(own,"x"),dy=number(e,"y")-number(own,"y"),dz=number(e,"z")-number(own,"z");
                double squared=dx*dx+dy*dy+dz*dz;if(!finite(squared)||squared<=0.000000000001)continue;
                if(squared<bestSquared||(squared==bestSquared&&best!=null&&e.optString("key").compareTo(best.optString("key"))<0)){best=e;bestSquared=squared;}
            }
            if(best==null){reasons.put(stream,"没有已确认的其他玩家坐标");continue;}
            double dx=number(best,"x")-number(own,"x"),dy=number(best,"y")-number(own,"y"),dz=number(best,"z")-number(own,"z");
            choices.put(stream,new Choice(stream,own,best,dx,dy,dz,Math.sqrt(bestSquared),scene,login,now));
        }
        return new Table(choices,reasons,logins);
    }
    private NearestTarget(){}
}