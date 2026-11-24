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
 *
 * <p>Chỉ đọc đúng một header đã cấu hình: header mà proxy đang dùng không ghi đè (vd {@code
 * cf-connecting-ip} khi không đứng sau Cloudflare) thì client tự đặt được.
 */
public final class ClientIpResolver {
    private final boolean trustProxy;
    private final List<IpRange> trusted;
    private final Metadata.Key<String> header;

    public ClientIpResolver(boolean trustProxy, List<String> trustedProxies, String header) {
        this.trustProxy = trustProxy;
        this.trusted = trustedProxies.stream().map(IpRange::parse).toList();
        this.header = Metadata.Key.of(header, Metadata.ASCII_STRING_MARSHALLER);
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
        String value = headers.get(header);
        String claimed = value == null ? "" : value.substring(value.lastIndexOf(',') + 1).strip();
        return IpRange.literal(claimed).orElse(address).getHostAddress();
    }
}
