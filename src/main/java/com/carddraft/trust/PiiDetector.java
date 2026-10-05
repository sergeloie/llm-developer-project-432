package com.carddraft.trust;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds contact details a real person can be reached on, and masks them.
 *
 * <p>The two checksums are the substance of this class, and both exist because the naive version
 * destroys the document. A taxpayer number is eleven digits and a card number is thirteen to
 * nineteen; supplier text is full of model numbers, article codes, capacities and weights, and a
 * detector that matched any run of digits would replace half of them. It would then have masked a
 * kettle's capacity and left a real person's number, which is the worst of both outcomes: the
 * document reads wrong and the card still leaks.
 *
 * <p>So neither is matched on shape alone. A taxpayer number is verified against its checksum, and
 * a card number against Luhn. Anything that fails either test is left exactly as it was — including
 * a value that looks like a taxpayer number but whose checksum is wrong, which in a supplier document
 * is far more likely to be an article number than a mistyped taxpayer number.
 *
 * <p>Masking replaces rather than removes. The token keeps the shape of the thing it replaced, so
 * a reader can still tell that a phone number was there and a model's attention is not drawn to a
 * gap. A removal would read as an omission in the source, which looks like a parsing failure.
 */
@org.springframework.stereotype.Component
public class PiiDetector {

    /**
     * A taxpayer number, with separators allowed inside.
     *
     * <p>Word boundaries rather than a plain digit run: without them this would match a substring of
     * a longer number and the checksum would be computed over the wrong digits.
     */
    private static final Pattern TAXPAYER = Pattern.compile("(?<![\\d-])(\\d{10}|\\d{12})(?![\\d-])");

    /**
     * A candidate run of digits that might be a phone number.
     *
     * <p>Deliberately wide, with the shape checked in {@link #isProbablyPhone}. A tight pattern here
     * would have to encode every national format, and the ones it missed would be exactly the ones
     * that leak — a detector that only knows one country's formatting protects one country. Matching
     * a candidate run and then validating it means an unfamiliar format can still be recognised as
     * long as its digits make sense.
     *
     * <p>Spaces and separators are included because every real supplier document writes a phone
     * number with them.
     */
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s-]{8,17}\\d");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** A card number, as its digits with spaces or dashes removed. */
    private static final Pattern CARD = Pattern.compile(
            "(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])");

    private static final String MASK = "[masked]";

    public Finding.Report scan(String text) {
        if (text == null || text.isBlank()) {
            return Finding.Report.clean(text);
        }

        List<Finding> findings = new ArrayList<>();
        String masked = text;

        // Order is a correctness concern, not a style one. The phone candidate is deliberately wide
        // so it can recognise a format it has not seen, and a ten-digit taxpayer number and a
        // sixteen-digit card number both look like phone candidates. Running the specific, verified
        // detectors first means they get their chance to recognise themselves; the wide phone rule
        // only ever sees text that is genuinely not one of them.
        masked = apply(masked, EMAIL, Finding.Kind.EMAIL, value -> true, findings);
        masked = apply(masked, TAXPAYER, Finding.Kind.TAXPAYER_NUMBER,
                PiiDetector::hasValidTaxpayerChecksum, findings);
        masked = apply(masked, CARD, Finding.Kind.CARD_NUMBER, PiiDetector::passesLuhn, findings);
        masked = apply(masked, PHONE, Finding.Kind.PHONE, PiiDetector::isProbablyPhone, findings);

        return new Finding.Report(findings, masked);
    }

    private String apply(String text, Pattern pattern, Finding.Kind kind,
                         java.util.function.Predicate<String> accept, List<Finding> findings) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();

        while (matcher.find()) {
            String value = matcher.group();
            if (!accept.test(value)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(value));
                continue;
            }
            findings.add(new Finding(kind, MASK, value, matcher.start()));
            matcher.appendReplacement(out, Matcher.quoteReplacement(MASK));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * A taxpayer number's checksum, for both forms.
     *
     * <p>Ten digits: weights 2 4 10 3 5 9 4 6 8, taken mod 11 then mod 10, must equal the tenth
     * digit. Twelve digits: the first eleven use weights 7 2 4 10 3 5 9 4 6 8 and the remainder
     * gives the eleventh; the twelfth is a second check over the first eleven using the same
     * weights.
     *
     * <p>Both are checked because both are issued. Validating only the ten-digit form would let every
     * twelve-digit taxpayer number through unmasked, and validating only the twelve would mangle
     * the shorter and far more common one.
     */
    public static boolean hasValidTaxpayerChecksum(String candidate) {
        String digits = candidate == null ? "" : candidate.replaceAll("\\D", "");
        return switch (digits.length()) {
            case 10 -> taxpayer10(digits);
            case 12 -> taxpayer12(digits);
            default -> false;
        };
    }

    private static boolean taxpayer10(String digits) {
        int[] weights = {2, 4, 10, 3, 5, 9, 4, 6, 8};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int check = (sum % 11) % 10;
        return check == (digits.charAt(9) - '0');
    }

    private static boolean taxpayer12(String digits) {
        int[] weights = {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int eleventh = (sum % 11) % 10;
        if (eleventh != (digits.charAt(10) - '0')) {
            return false;
        }
        // The twelfth digit is not a second pass over the first eleven. It is the eleventh digit
        // doubled, plus the tenth, mod 11 - a different rule from the ten-digit form, and getting
        // it wrong accepts every twelve-digit number with a plausible eleventh.
        int twelfth = ((eleventh * 2) + (digits.charAt(9) - '0')) % 11;
        if (twelfth == 10) {
            twelfth = 0;
        }
        return twelfth == (digits.charAt(11) - '0');
    }

    /**
     * Luhn, for a card number.
     *
     * <p>The check digit is not counted, so this answers "is this a plausible card number" rather
     * than "is this the right card". That is the question worth asking of supplier text: a supplier
     * who wrote a real card number wrote one that validates.
     */
    public static boolean passesLuhn(String candidate) {
        String digits = candidate == null ? "" : candidate.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = true;
        for (int i = digits.length() - 2; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        int check = digits.charAt(digits.length() - 1) - '0';
        return (sum + check) % 10 == 0;
    }

/**
     * Whether a candidate is a phone number rather than a specification.
     *
     * <p>Three checks, each learned from something real in supplier documents.
     *
     * <p>Length and national prefix. A Russian number is eleven digits starting with 7, or ten
     * starting with 8 — the 7 form including the country code. Without the count, a ten-digit
     * article number beginning "77" is indistinguishable from a phone number, and that is the single
     * most common way a naive detector destroys a specification.
     *
     * <p>An explicit {@code +} allows a longer number, because a country code is then known to be
     * present and the shape argument does not apply.
     *
     * <p>And the operator code must not be 000, which is how "8 (000) 000-00-00" appears in
     * specification tables. Testing the operator code specifically rather than any pair of zeroes
     * is what keeps real 900-series mobile numbers, whose subscriber part legitimately starts 00.
     */
    static boolean isProbablyPhone(String candidate) {
        String digits = candidate.replaceAll("\\D", "");
        boolean international = candidate.stripLeading().startsWith("+");

        if (international) {
            return digits.length() >= 11 && digits.length() <= 15;
        }
        if (digits.length() == 11 && digits.charAt(0) == '7') {
            return !digits.substring(1, 4).equals("000");
        }
        if (digits.length() == 10 && digits.charAt(0) == '8') {
            return !digits.substring(1, 4).equals("000");
        }
        return false;
    }
}