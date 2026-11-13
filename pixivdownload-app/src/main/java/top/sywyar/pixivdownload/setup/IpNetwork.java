package top.sywyar.pixivdownload.setup;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** 可信代理与程序密钥共用的数值 IP 网段；不解析主机名。 */
record IpNetwork(byte[] network, int prefixLength) {

        static IpNetwork parse(String value) {
            int slash = value.indexOf('/');
            if (slash <= 0 || slash != value.lastIndexOf('/')) {
                throw new IllegalArgumentException("CIDR prefix is required");
            }
            byte[] address = parseNumericAddress(value.substring(0, slash).trim());
            if (address == null) {
                throw new IllegalArgumentException("Invalid CIDR address");
            }
            int prefix;
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix", e);
            }
            int bits = address.length * 8;
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Invalid CIDR prefix");
            }
            byte[] network = address.clone();
            for (int bit = prefix; bit < bits; bit++) {
                network[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
            }
            return new IpNetwork(network, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            for (int bit = 0; bit < prefixLength; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    static byte[] parseNumericAddress(String value) {
        if ((value == null || value.trim().isEmpty()) || value.indexOf('%') >= 0) {
            return null;
        }
        if (value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) {
            String[] parts = value.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < parts.length; i++) {
                int part = Integer.parseInt(parts[i]);
                if (part > 255) {
                    return null;
                }
                bytes[i] = (byte) part;
            }
            return bytes;
        }
        if (value.indexOf(':') < 0 || !value.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

}
