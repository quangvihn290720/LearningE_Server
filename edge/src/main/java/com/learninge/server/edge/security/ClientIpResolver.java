package com.learninge.server.edge.security;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * IP để giới hạn tần suất (TM-04). Chỉ tin header của proxy khi được cấu hình <b>và</b> kết nối đến
 * từ proxy tin cậy; lấy phần tử cuối của {@code x-forwarded-for} vì phần đầu do client tự viết
 * được.
 */
public final class ClientIpResolver {
    private static final Metadata.Key<String> CF_IP =
            Metadata.Key.of("cf-connecting-ip", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> FORWARDED =
            Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER);

    private final boolean trustProxy;
    private final List<IpRange> trusted;

    public ClientIpResolver(boolean trustProxy, List<String> trustedProxies) {
        this.trustProxy = trustProxy;
        this.trusted = trustedProxies.stream().map(IpRange::parse).toList();
    }

    String resolve(Attributes attributes, Metadata headers) {
        SocketAddress peer = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (!(peer instanceof InetSocketAddress socket) || socket.getAddress() == null) {
            return "unknown";
        }
        InetAddress address = socket.getAddress();
        if (!trustProxy || trusted.stream().noneMatch(range -> range.contains(address))) {
            return address.getHostAddress();
        }
        String claimed = headers.get(CF_IP);
        if (claimed == null) {
            String forwarded = headers.get(FORWARDED);
            claimed =
                    forwarded == null ? "" : forwarded.substring(forwarded.lastIndexOf(',') + 1).strip();
        }
        return IpRange.literal(claimed).orElse(address).getHostAddress();
    }
}
