import com.craftstudio.cslauncher.multiplayer.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Desktop end-to-end test for the Virtual LAN core (no Android needed): runs a host session and a
 * guest session in one JVM against the real tunnel engine and the real public relay nodes, with a
 * fake "Open to LAN" Minecraft world. See tools/vlan-harness/README.md.
 */
public class Harness {
    static final String ENGINE_DIR = System.getProperty("engine.dir", "/tmp/jnitest/x86_64");
    static final String WORK_DIR = System.getProperty("work.dir", "/tmp/harness");
    static class Env implements TunnelEnvironment {
        final File work; final String tag;
        Env(String tag){ this.tag=tag; work=new File(WORK_DIR + "/work-" + tag); work.mkdirs(); }
        public File coreBinary(){ return new File(ENGINE_DIR, "libeasytier_core.so"); }
        public File cliBinary(){ return new File(ENGINE_DIR, "libeasytier_cli.so"); }
        public File workDir(){ return work; }
        public String shell(){ return "/bin/sh"; }
        public int myPid(){ try { return Integer.parseInt(new File("/proc/self").getCanonicalFile().getName()); } catch(Exception e){ return -1; } }
        public void killProcess(int pid){ try { Runtime.getRuntime().exec(new String[]{"kill","-9",""+pid}).waitFor(); } catch(Exception ignored){} }
        public void log(String line){ System.out.println("["+tag+"] "+line); }
    }

    /** Fake "Open to LAN" world: modern status ping + accepts connections; sends the multicast ad */
    static class FakeWorld implements Runnable {
        ServerSocket ss; volatile boolean run=true; Thread t; Thread ad; int port;
        FakeWorld() throws IOException { ss=new ServerSocket(0, 8, InetAddress.getByName("0.0.0.0")); port=ss.getLocalPort(); t=new Thread(this); t.setDaemon(true); t.start();
            ad=new Thread(()->{ try(DatagramSocket ds=new DatagramSocket()){ byte[] b=("[MOTD]Raj - Survival World[/MOTD][AD]"+port+"[/AD]").getBytes(StandardCharsets.UTF_8);
                while(run){ ds.send(new DatagramPacket(b,b.length,InetAddress.getByName("224.0.2.60"),4445)); Thread.sleep(1500);} }catch(Exception e){ System.out.println("ad err "+e);} }); ad.setDaemon(true); ad.start(); }
        public void run(){ while(run){ try{ Socket s=ss.accept(); new Thread(()->serve(s)).start(); }catch(IOException e){ break; } } }
        void serve(Socket s){ try{ s.setSoTimeout(5000); DataInputStream in=new DataInputStream(s.getInputStream()); OutputStream out=s.getOutputStream();
            readVar(in); int id=readVar(in); if(id!=0){ s.close(); return; } readVar(in); int hl=readVar(in); in.skipBytes(hl); in.readUnsignedShort(); int next=readVar(in);
            if(next==1){ readVar(in); readVar(in); String json="{\"version\":{\"name\":\"1.20.1\",\"protocol\":763},\"players\":{\"max\":8,\"online\":1},\"description\":{\"text\":\"Raj - Survival World\"}}";
                byte[] j=json.getBytes(StandardCharsets.UTF_8); ByteArrayOutputStream p=new ByteArrayOutputStream(); p.write(0); writeVar(p,j.length); p.write(j);
                ByteArrayOutputStream f=new ByteArrayOutputStream(); writeVar(f,p.size()); f.write(p.toByteArray()); out.write(f.toByteArray()); out.flush(); }
            s.close(); }catch(Exception e){} }
        void close(){ run=false; try{ss.close();}catch(IOException e){} }
        static int readVar(InputStream in) throws IOException { int r=0,sh=0; while(true){ int b=in.read(); if(b<0) throw new IOException(); r|=(b&0x7f)<<sh; if((b&0x80)==0) return r; sh+=7; } }
        static void writeVar(OutputStream o,int v) throws IOException { while((v&~0x7f)!=0){ o.write((v&0x7f)|0x80); v>>>=7;} o.write(v); }
    }

    static VirtualLanSession.Listener listener(String tag){ return new VirtualLanSession.Listener(){
        VirtualLanState.Phase last; int lastMembers=-1; boolean lastOpen;
        public void onStateChanged(VirtualLanState s){ if(s.phase!=last||s.members.size()!=lastMembers||s.worldOpen!=lastOpen){ last=s.phase; lastMembers=s.members.size(); lastOpen=s.worldOpen;
            System.out.println("["+tag+"] phase="+s.phase+" reason="+s.reason+" relays="+s.relayNodesConnected+" vip="+s.localVip+" hostVip="+s.hostVip+" world="+s.worldOpen+"/"+s.worldName+"/"+s.versionName+" lan="+s.lanPort+" proxy="+s.proxyPort+" dir="+s.directoryPort+" localGame="+s.localGamePort+" members="+s.members.size()+" hostLat="+s.hostLatencyMs+" inGame="+s.inGame); } }
        public void onFinished(VirtualLanState s){ System.out.println("["+tag+"] FINISHED "+s.phase+" "+s.reason+" "+s.detail); } }; }

    public static void main(String[] a) throws Exception {
        FakeWorld world=new FakeWorld(); System.out.println("fake world on "+world.port);
        SessionCode code=SessionCode.generate(); System.out.println("code "+code);
        VirtualLanSession.Params hp=new VirtualLanSession.Params(); hp.role=VirtualLanState.Role.HOST; hp.code=code; hp.playerName="Raj"; hp.allowRemoteNodeList=false;
        VirtualLanSession host=new VirtualLanSession(new Env("host"), new File("/tmp/harness/cache"), hp, listener("HOST")); host.start();
        Thread.sleep(500);
        VirtualLanSession.Params gp=new VirtualLanSession.Params(); gp.role=VirtualLanState.Role.GUEST; gp.code=code; gp.playerName="Priya"; gp.allowRemoteNodeList=false; gp.guestGamePort=25575;
        VirtualLanSession guest=new VirtualLanSession(new Env("guest"), new File("/tmp/harness/cache"), gp, listener("GUEST")); guest.start();
        long deadline=System.currentTimeMillis()+120000;
        while(System.currentTimeMillis()<deadline && !guest.state().phase.isConnected() && !guest.isFinished()) Thread.sleep(500);
        VirtualLanState gs=guest.state();
        for(String l: guest.engineLog()) System.out.println("  elog| "+l);
        System.out.println("GUEST connected? "+gs.phase+" localGame="+gs.localGamePort+" world="+gs.worldOpen+" "+gs.worldName);
        if(gs.phase.isConnected()){
            Thread.sleep(3000);
            MinecraftPinger.Status st=MinecraftPinger.ping("127.0.0.1", gs.localGamePort, 5000);
            System.out.println("PING THROUGH TUNNEL -> "+st);
            Thread.sleep(3000);
            System.out.println("host members: "+host.state().members.size()+" guest sees members "+guest.state().members.size()+" events="+host.state().events.size());
            for(VirtualLanState.Member m: host.state().members) System.out.println("  member "+m.name+" "+m.vip+" lat="+m.latencyMs+" direct="+m.direct);
            System.out.println("-- closing world");
            world.close();
            long d2=System.currentTimeMillis()+20000; while(System.currentTimeMillis()<d2 && guest.state().worldOpen) Thread.sleep(500);
            System.out.println("guest worldOpen now "+guest.state().worldOpen+" host worldOpen "+host.state().worldOpen);
            System.out.println("-- reopening world");
            world=new FakeWorld(); System.out.println("fake world on "+world.port);
            d2=System.currentTimeMillis()+25000; while(System.currentTimeMillis()<d2 && !guest.state().worldOpen) Thread.sleep(500);
            System.out.println("guest worldOpen now "+guest.state().worldOpen+" lan="+guest.state().lanPort+" ping="+MinecraftPinger.ping("127.0.0.1", gs.localGamePort, 5000));
            if (a.length>0 && a[0].equals("kick")) { System.out.println("-- kicking"); host.kick(host.state().members.get(0).id); }
            else { System.out.println("-- host ending"); host.end(VirtualLanState.Reason.LEFT); }
            d2=System.currentTimeMillis()+20000; while(System.currentTimeMillis()<d2 && !guest.isFinished()) Thread.sleep(500);
            System.out.println("guest final "+guest.state().phase+" "+guest.state().reason);
            if(!host.isFinished()) host.end(VirtualLanState.Reason.LEFT);
        } else { host.end(VirtualLanState.Reason.LEFT); guest.end(VirtualLanState.Reason.LEFT); }
        long d3=System.currentTimeMillis()+15000; while(System.currentTimeMillis()<d3 && !(host.isFinished()&&guest.isFinished())) Thread.sleep(300);
        Thread.sleep(1500);
        Process p=Runtime.getRuntime().exec(new String[]{"sh","-c","ps -o pid,cmd -C libeasytier_core.so | grep -c libeasytier || true"}); 
        System.out.println("remaining easytier processes: "+new BufferedReader(new InputStreamReader(p.getInputStream())).readLine());
        world.close();
        System.exit(0);
    }
}
