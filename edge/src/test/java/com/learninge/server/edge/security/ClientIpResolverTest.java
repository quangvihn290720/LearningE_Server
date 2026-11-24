package com.learninge.server.edge.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

class ClientIpResolverTest {
    private static final List<String> LOOPBACK = List.of("127.0.0.0/8", "::1/128");

    private static String resolve(boolean trust, String peer, String forwarded) {
        Metadata headers = new Metadata();
        if (forwarded != null) {
            headers.put(Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER), forwarded);
        }
        Attributes attributes =
                Attributes.newBuilder()
                        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress(peer, 1234))
                        .build();
        return new ClientIpResolver(trust, LOOPBACK, "x-forwarded-for").resolve(attributes, headers);
    }

    @Test
    void ignoresForwardedHeadersUnlessThePeerIsATrustedProxy() {
        assertThat(resolve(false, "127.0.0.1", "9.9.9.9")).isEqualTo("127.0.0.1");
        assertThat(resolve(true, "8.8.8.8", "9.9.9.9")).isEqualTo("8.8.8.8");
        assertThat(resolve(true, "127.0.0.1", "9.9.9.9")).isEqualTo("9.9.9.9");
    }

    @Test
    void usesTheLastHopBecauseEarlierOnesAreClientControlled() {
        assertThat(resolve(true, "127.0.0.1", "1.1.1.1, 2.2.2.2, 3.3.3.3")).isEqualTo("3.3.3.3");
    }

    @Test
    void fallsBackToThePeerWhenTheHeaderIsNotAnIp() {
        assertThat(resolve(true, "127.0.0.1", "evil.example")).isEqualTo("127.0.0.1");
        assertThat(resolve(true, "127.0.0.1", "abc")).isEqualTo("127.0.0.1");
        assertThat(resolve(true, "127.0.0.1", null)).isEqualTo("127.0.0.1");
    }

    @Test
    void readsOnlyTheConfiguredHeader() {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("cf-connecting-ip", Metadata.ASCII_STRING_MARSHALLER), "6.6.6.6");
        headers.put(Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER), "3.3.3.3");
        Attributes loopback =
                Attributes.newBuilder()
                        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress("127.0.0.1", 1234))
                        .build();
        assertThat(new ClientIpResolver(true, LOOPBACK, "x-forwarded-for").resolve(loopback, headers))
                .isEqualTo("3.3.3.3");
        assertThat(new ClientIpResolver(true, LOOPBACK, "cf-connecting-ip").resolve(loopback, headers))
                .isEqualTo("6.6.6.6");
    }

    @Test
    void matchesCidrRanges() throws Exception {
        IpRange range = IpRange.parse("10.1.0.0/16");
        assertThat(range.contains(java.net.InetAddress.getByName("10.1.200.3"))).isTrue();
        assertThat(range.contains(java.net.InetAddress.getByName("10.2.0.1"))).isFalse();
        assertThat(range.contains(java.net.InetAddress.getByName("::1"))).isFalse();
    }
}
