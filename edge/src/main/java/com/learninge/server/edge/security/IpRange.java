package com.learninge.server.edge.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/** Dải IP dạng {@code 10.0.0.0/8} hoặc một IP. Không bao giờ tra DNS. */
record IpRange(byte[] network, int prefix) {
  private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

  static IpRange parse(String text) {
    String[] parts = text.strip().split("/", 2);
    InetAddress address =
        literal(parts[0])
            .orElseThrow(() -> new IllegalArgumentException("Không phải IP: " + parts[0]));
    byte[] network = address.getAddress();
    int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
    return new IpRange(network, prefix);
  }

  /** Chỉ nhận IP viết thẳng; tên miền → rỗng. */
  static Optional<InetAddress> literal(String text) {
    if (!IPV4.matcher(text).matches() && !IPV6.matcher(text).matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.getByName(text));
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }

  boolean contains(InetAddress address) {
    byte[] candidate = address.getAddress();
    if (candidate.length != network.length) {
      return false;
    }
    for (int bit = 0; bit < prefix; bit++) {
      int mask = 0x80 >> (bit % 8);
      if ((candidate[bit / 8] & mask) != (network[bit / 8] & mask)) {
        return false;
      }
    }
    return true;
  }
}
