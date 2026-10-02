package com.ridehailing.shared;

/** Phone numbers in E.164 form, such as {@code +919845012345}. */
public final class Phones {

    private Phones() {
    }

    /** For logs: the first three characters and the last four digits, such as {@code +91******2345} (LLD §12.5). */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 9) {
            return "***";
        }
        return phone.substring(0, 3) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 4);
    }
}
