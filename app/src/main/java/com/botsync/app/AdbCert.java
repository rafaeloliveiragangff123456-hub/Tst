package com.botsync.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Gera o certificado X.509 autoassinado do app para a depuração por Wi-Fi, montando o DER na mão
 * (sem depender de sun.security nem de BouncyCastle).
 */
public final class AdbCert {
    private AdbCert() {}

    private static byte[] tlv(int tag, byte[]... partes) {
        ByteArrayOutputStream corpo = new ByteArrayOutputStream();
        for (byte[] p : partes) corpo.write(p, 0, p.length);
        byte[] c = corpo.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = c.length;
        if (n < 128) {
            o.write(n);
        } else if (n < 256) {
            o.write(0x81);
            o.write(n);
        } else {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n & 0xff);
        }
        o.write(c, 0, c.length);
        return o.toByteArray();
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes());
    }

    public static Certificate gerar(PublicKey pub, PrivateKey priv) throws Exception {
        // AlgorithmIdentifier: sha256WithRSAEncryption (1.2.840.113549.1.1.11) + NULL
        byte[] algoritmo = tlv(0x30,
                tlv(0x06, new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}),
                new byte[]{0x05, 0x00});

        // Nome: CN=BotSync
        byte[] nome = tlv(0x30, tlv(0x31, tlv(0x30,
                tlv(0x06, new byte[]{0x55, 0x04, 0x03}),
                tlv(0x0C, "BotSync".getBytes()))));

        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] = (byte) ((serial[0] & 0x3F) | 0x40); // positivo e sem zero à esquerda

        Date ini = new Date(System.currentTimeMillis() - 86400000L);
        Date fim = new Date(System.currentTimeMillis() + 365L * 86400000L);

        byte[] tbs = tlv(0x30,
                tlv(0xA0, tlv(0x02, new byte[]{0x02})), // versão 3
                tlv(0x02, serial),
                algoritmo,
                nome,
                tlv(0x30, utcTime(ini), utcTime(fim)),
                nome,
                pub.getEncoded());

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(priv);
        s.update(tbs);
        byte[] assinatura = s.sign();

        byte[] bits = new byte[assinatura.length + 1];
        System.arraycopy(assinatura, 0, bits, 1, assinatura.length);

        byte[] der = tlv(0x30, tbs, algoritmo, tlv(0x03, bits));
        return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }
}
