package dev.audioscope;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.*;
import java.util.Date;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.x509.X509V3CertificateGenerator;

/**
 * One atomic identity file. Legacy keys are migrated without changing Android's trusted public key.
 */
final class AdbIdentityStore {
  final PrivateKey key;
  final Certificate cert;

  AdbIdentityStore(File directory, File legacy) throws Exception {
    File identity = new File(directory, "adb-identity.bin");
    if (identity.exists()) {
      try (DataInputStream in = new DataInputStream(new FileInputStream(identity))) {
        if (in.readInt() != 0x41534944) throw new IOException("Invalid saved ADB identity");
        key = decode(block(in));
        cert =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(block(in)));
        if (in.read() != -1) throw new IOException("Unexpected saved identity data");
      }
      validate(key, cert);
      return;
    }
    File oldKey = new File(legacy, "adb-private.der");
    File oldCert = new File(legacy, "adb-cert.der");
    PublicKey publicKey;
    if (oldKey.exists()) {
      // Never replace a damaged identity behind the user's back.
      key = decode(Files.readAllBytes(oldKey.toPath()));
      RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) key;
      publicKey =
          KeyFactory.getInstance("RSA")
              .generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
    } else {
      if (oldCert.exists())
        throw new IOException("ADB private key is missing; saved identity was preserved");
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      key = pair.getPrivate();
      publicKey = pair.getPublic();
    }
    cert =
        oldCert.exists()
            ? CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(oldCert.toPath())))
            : certificate(key, publicKey);
    validate(key, cert);
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Cannot save ADB identity");
    File temporary = new File(directory, "adb-identity.tmp");
    try (FileOutputStream file = new FileOutputStream(temporary);
        DataOutputStream out = new DataOutputStream(file)) {
      out.writeInt(0x41534944);
      for (byte[] bytes : new byte[][] {key.getEncoded(), cert.getEncoded()}) {
        out.writeInt(bytes.length);
        out.write(bytes);
      }
      out.flush();
      file.getFD().sync();
    }
    try {
      Files.move(temporary.toPath(), identity.toPath(), StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary.toPath(), identity.toPath());
    }
  }

  private static byte[] block(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 1 || count > 32768) throw new IOException("Invalid identity block");
    byte[] bytes = new byte[count];
    in.readFully(bytes);
    return bytes;
  }

  private static PrivateKey decode(byte[] bytes) throws Exception {
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
  }

  private static void validate(PrivateKey key, Certificate cert) throws Exception {
    Signature signature = Signature.getInstance("SHA256withRSA");
    byte[] challenge =
        "AudioScope identity consistency".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    signature.initSign(key);
    signature.update(challenge);
    byte[] signed = signature.sign();
    signature.initVerify(cert.getPublicKey());
    signature.update(challenge);
    if (!signature.verify(signed))
      throw new IOException("Saved ADB key and certificate do not match");
  }

  @SuppressWarnings("deprecation")
  private static Certificate certificate(PrivateKey key, PublicKey publicKey) throws Exception {
    X509V3CertificateGenerator cg = new X509V3CertificateGenerator();
    cg.setSerialNumber(new BigInteger(120, new SecureRandom()));
    X509Name name = new X509Name("CN=AudioScope");
    cg.setIssuerDN(name);
    cg.setSubjectDN(name);
    cg.setNotBefore(new Date(System.currentTimeMillis() - 86400000L));
    cg.setNotAfter(new Date(System.currentTimeMillis() + 10L * 365 * 86400000));
    cg.setPublicKey(publicKey);
    cg.setSignatureAlgorithm("SHA256withRSA");
    return cg.generate(key);
  }
}
