package heritier.ntaganira.highbytes.wms.common.db;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.db
 * - File       : DbRefusal.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Turns a database control's refusal into the reason a user reads
 * </pre>
 */

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.util.Optional;
import java.util.Set;

/**
 * Reads the reason out of a database refusal.
 *
 * <p>The triggers raise their refusals with a human message: 23Z02 for a
 * workflow or document control, 23Z01 for an access control, check_violation
 * (23514) for a malformed line, restrict_violation (23001) for a locked
 * business date, and the default P0001 for the older ledger guards. Anything
 * else is a bug or an outage, and stays an exception.
 *
 * <p>A CHECK constraint (as opposed to a trigger) names the constraint in its
 * message, which means nothing to a user; those get a sentence naming the
 * rule instead.
 */
public final class DbRefusal {

    private static final Set<String> HUMAN_STATES = Set.of("23Z02", "23Z01", "23514", "23001", "P0001");

    private DbRefusal() {}

    /** The reason, when the failure is a control speaking; empty for anything else. */
    public static Optional<String> reason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            // A deadlock or serialization failure is not a control speaking, but the user's answer is the same
            // plain one: nothing changed, try again.
            if (cause instanceof PSQLException clash
                    && ("40P01".equals(clash.getSQLState()) || "40001".equals(clash.getSQLState()))) {
                return Optional.of("Another posting touched the same stock at the same moment, so nothing was "
                                   + "changed. Try again.");
            }
            if (cause instanceof PSQLException psql
                    && psql.getSQLState() != null
                    && HUMAN_STATES.contains(psql.getSQLState())) {
                ServerErrorMessage server = psql.getServerErrorMessage();
                if (server != null && server.getConstraint() != null && "23514".equals(psql.getSQLState())) {
                    return Optional.of("The entry breaks a rule of the database ("
                                       + server.getConstraint().replace('_', ' ') + "). Check the figures and try again.");
                }
                String message = server != null && server.getMessage() != null
                        ? server.getMessage() : psql.getMessage();
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    /** The name of the constraint a unique or check violation names, when there is one. */
    public static Optional<String> constraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null
                    && psql.getServerErrorMessage().getConstraint() != null) {
                return Optional.of(psql.getServerErrorMessage().getConstraint());
            }
        }
        return Optional.empty();
    }

    /**
     * The exception to throw for a failure: a {@link ControlRefusedException}
     * carrying the database's reason when a control refused, otherwise the
     * failure itself.
     */
    public static RuntimeException asRefusal(RuntimeException failure) {
        return reason(failure)
                .<RuntimeException>map(ControlRefusedException::new)
                .orElse(failure);
    }
}
