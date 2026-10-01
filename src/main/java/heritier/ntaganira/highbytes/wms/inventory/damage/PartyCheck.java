package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : PartyCheck.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Whether a person is independent of the transfer or delivery a loss or a return is about, in plain words
 * </pre>
 */

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.UUID;

/**
 * The independence rules of a transit loss and a customer return, asked of the
 * database rather than re-implemented.
 *
 * <p>V14 decides them ({@code dmg_party_of}): whoever raised, dispatched, signed,
 * received or recorded the arrival of a transfer does not write off what went
 * missing from it, and whoever drew up a delivery, let it out, raised its
 * authorization or signed any step of it does not take it back. The verdict here is that very function, so this class cannot drift from
 * what the database will refuse at the row; what it adds is the sentence a person
 * reads instead of a constraint name.
 */
@Service
@Transactional(readOnly = true)
public class PartyCheck {

    private final JdbcClient jdbc;

    public PartyCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Why {@code person} may not write a loss against this transfer or a return against
     * this delivery note, or null when they may. {@code kind} is TRANSIT_LOSS or
     * CUSTOMER_RETURN; for any other kind nobody is excluded.
     */
    public String reason(UUID person, String kind, UUID transferId, UUID deliveryNoteId) {
        if (person == null || !("TRANSIT_LOSS".equals(kind) || "CUSTOMER_RETURN".equals(kind))) return null;
        String party = jdbc.sql("SELECT dmg_party_of(:person::uuid, :kind::text, :transfer::uuid, :note::uuid)")
                .param("person", person, Types.OTHER)
                .param("kind", kind)
                .param("transfer", transferId, Types.OTHER)
                .param("note", deliveryNoteId, Types.OTHER)
                .query(String.class).optional().orElse(null);
        if (party == null) return null;
        String subject = jdbc.sql("SELECT dmg_subject(:kind::text, :transfer::uuid, :note::uuid)")
                .param("kind", kind)
                .param("transfer", transferId, Types.OTHER)
                .param("note", deliveryNoteId, Types.OTHER)
                .query(String.class).single();
        return describe(party, kind, subject);
    }

    /** The sentence for a verdict. Pure, so it is tested without a database. */
    static String describe(String party, String kind, String subject) {
        String what = switch (party) {
            case "raised"     -> "You raised " + subject;
            case "dispatched" -> "You dispatched " + subject;
            case "signed"     -> "You signed a step of " + subject;
            case "received"   -> "You posted the receipt of " + subject;
            case "recorded"   -> "You recorded the arrival of " + subject;
            case "released"   -> "You let the goods of " + subject + " out, posting it at the gate";
            case "loaded"     -> "You drew up " + subject;
            case "authorized" -> "You raised the authorization behind " + subject;
            case "approved"   -> "You signed the authorization behind " + subject;
            default           -> "You are a party to " + subject;
        };
        return what + ", so you cannot " + ("CUSTOMER_RETURN".equals(kind)
                ? "take back what came back from it"
                : "write off what went missing from it")
                + ". Whoever sent, received or let out the goods does not also write off what went missing or "
                + "take back what was returned.";
    }
}
