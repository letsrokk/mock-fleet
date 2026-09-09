package com.github.letsrokk.mockops;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record AllowedVersionRange(List<BigInteger> lower, boolean includesLower,
                           List<BigInteger> upper, boolean includesUpper) {
    private static final String VERSION = "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?";
    private static final Pattern INTERVAL = Pattern.compile("^([\\[(])" + VERSION + "," + VERSION + "([\\])])$");

    static AllowedVersionRange parse(String value) {
        Matcher match = INTERVAL.matcher(value == null ? "" : value);
        if (!match.matches()) {
            throw new IllegalArgumentException("allowedVersionRange must be a bounded version interval, for example [3.0,4.0).");
        }
        List<BigInteger> lower = version(match, 2);
        List<BigInteger> upper = version(match, 5);
        boolean includesLower = "[".equals(match.group(1));
        boolean includesUpper = "]".equals(match.group(8));
        int order = compare(lower, upper);
        if (order > 0 || (order == 0 && !(includesLower && includesUpper))) {
            throw new IllegalArgumentException("allowedVersionRange must not be reversed or empty.");
        }
        return new AllowedVersionRange(lower, includesLower, upper, includesUpper);
    }

    boolean contains(WireMockTag tag) {
        List<BigInteger> version = List.of(BigInteger.valueOf(3), BigInteger.valueOf(tag.minor()),
                BigInteger.valueOf(tag.patch()));
        int from = compare(version, lower);
        int to = compare(version, upper);
        return (from > 0 || (from == 0 && includesLower)) && (to < 0 || (to == 0 && includesUpper));
    }

    private static List<BigInteger> version(Matcher matcher, int start) {
        return List.of(new BigInteger(matcher.group(start)), new BigInteger(matcher.group(start + 1)),
                matcher.group(start + 2) == null ? BigInteger.ZERO : new BigInteger(matcher.group(start + 2)));
    }

    private static int compare(List<BigInteger> left, List<BigInteger> right) {
        for (int index = 0; index < 3; index++) {
            int order = left.get(index).compareTo(right.get(index));
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }
}
