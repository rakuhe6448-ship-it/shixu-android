import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Resumable HTTPS downloads for unreliable connections. TLS verification stays enabled. */
public final class DownloadVerified {
    public static void main(String[] args) throws Exception {
        URI source = URI.create(args[0]);
        if (!source.getScheme().equals("https")) throw new IllegalArgumentException("HTTPS required");
        Path output = Path.of(args[1]).toAbsolutePath();
        String expectedHash = args[2];
        long size = Long.parseLong(args[3]);
        int connections = args.length > 4 ? Integer.parseInt(args[4]) : 8;
        if (Files.exists(output) && Files.size(output) == size && sha1(output).equalsIgnoreCase(expectedHash)) {
            System.out.println("Already verified: " + output.getFileName()); return;
        }
        Path parts = Path.of(output + ".parts");
        Files.createDirectories(parts);
        int chunkSize = 1024 * 1024;
        int count = (int)((size + chunkSize - 1)/chunkSize);
        ExecutorService pool = Executors.newFixedThreadPool(connections);
        List<Future<?>> futures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                Path part = parts.resolve(Integer.toString(index));
                long start = (long)index * chunkSize;
                long length = Math.min(chunkSize, size-start);
                try {
                    if (Files.exists(part) && Files.size(part) > length) throw new IOException("Oversized partial download");
                    int failures = 0;
                    while (!Files.exists(part) || Files.size(part) < length) {
                        long have = Files.exists(part) ? Files.size(part) : 0;
                        HttpURLConnection c = (HttpURLConnection)source.toURL().openConnection();
                        c.setConnectTimeout(20000); c.setReadTimeout(25000);
                        c.setInstanceFollowRedirects(false);
                        c.setRequestProperty("Range", "bytes="+(start+have)+"-"+(start+length-1));
                        c.setRequestProperty("Accept-Encoding", "identity");
                        c.setRequestProperty("User-Agent", "ShiXu-build-download/1.0");
                        try {
                            if (c.getResponseCode()!=206 || !Objects.equals(c.getHeaderField("Content-Range"), "bytes "+(start+have)+"-"+(start+length-1)+"/"+size))
                                throw new IOException("Unexpected range response: " + c.getResponseCode());
                            try(InputStream in=c.getInputStream(); OutputStream out=Files.newOutputStream(part,StandardOpenOption.CREATE,StandardOpenOption.APPEND)) {
                                byte[] buffer=new byte[16384]; int read;
                                while((read=in.read(buffer))!=-1) {
                                    if (have+read>length) throw new IOException("Oversized response");
                                    out.write(buffer,0,read); have+=read;
                                }
                            }
                            if(have<length) throw new EOFException("Incomplete range");
                        } catch(IOException e) {
                            if(++failures>16) throw e;
                            Thread.sleep(Math.min(3000,200L*failures));
                        } finally { c.disconnect(); }
                    }
                    int done=completed.incrementAndGet();
                    if(done%20==0 || done==count) System.out.println("Verified transport ranges: "+done+"/"+count);
                } catch(Exception e) { throw new RuntimeException("Chunk "+index+" failed",e); }
            }));
        }
        pool.shutdown();
        try { for(Future<?> future:futures) future.get(); }
        catch(Exception e) { pool.shutdownNow(); throw e; }
        Path assembled=Path.of(output+".assembling");
        try(OutputStream out=Files.newOutputStream(assembled)) {
            for(int i=0;i<count;i++) Files.copy(parts.resolve(Integer.toString(i)),out);
        }
        if(Files.size(assembled)!=size || !sha1(assembled).equalsIgnoreCase(expectedHash)) throw new SecurityException("Official checksum mismatch; download will not be used");
        Files.move(assembled,output,StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Official SHA-1 verified: "+output.getFileName());
    }
    private static String sha1(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-1");
        try(InputStream in=Files.newInputStream(file)) { byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
}
