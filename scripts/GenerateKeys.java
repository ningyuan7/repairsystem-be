import java.nio.file.*;
import java.security.*;
import java.util.Base64;
/** Local development key generation; never overwrites existing keys. */
class GenerateKeys {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args.length==0?"config/keys":args[0]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path privateKey=directory.resolve("private.pem"),publicKey=directory.resolve("public.pem");
        if(Files.exists(privateKey)||Files.exists(publicKey)) throw new IllegalStateException("Keys already exist; refusing to overwrite");
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(3072); var pair=generator.generateKeyPair();
        var encoder=Base64.getMimeEncoder(64,new byte[]{10});
        Files.writeString(privateKey,"-----BEGIN PRIVATE KEY-----\n"+encoder.encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n",StandardOpenOption.CREATE_NEW);
        Files.writeString(publicKey,"-----BEGIN PUBLIC KEY-----\n"+encoder.encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n",StandardOpenOption.CREATE_NEW);
        System.out.println("Created local keys in "+directory);
    }
}
