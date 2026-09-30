package heritier.ntaganira.highbytes.wms.common;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common
 * - File       : DbRefusalTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A lost race is told apart from a control's refusal
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ContentionException;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deadlock (40P01) or serialization failure (40001) is not a control
 * refusing anything: nothing was decided, two requests met. It is answered
 * with the same plain "try again", but it must be classified as contention so
 * the audit trail is not filled with REJECT rows for things nobody refused.
 */
class DbRefusalTest {

    private static PSQLException deadlock() {
        return new PSQLException("deadlock detected", PSQLState.DEADLOCK_DETECTED);
    }

    @Test
    void aDeadlockAndASerializationFailureAreContentionWhereverTheyAreWrapped() {
        assertThat(DbRefusal.isContention(deadlock())).isTrue();
        assertThat(DbRefusal.isContention(new CannotAcquireLockException("x", deadlock()))).isTrue();
        assertThat(DbRefusal.isContention(new TransactionSystemException("commit",
                new PSQLException("could not serialize", PSQLState.SERIALIZATION_FAILURE)))).isTrue();
    }

    @Test
    void contentionKeepsTheTryAgainMessageButIsAContentionException() {
        RuntimeException failure = DbRefusal.asRefusal(new CannotAcquireLockException("x", deadlock()));
        assertThat(failure).isInstanceOf(ContentionException.class);
        assertThat(failure.getMessage()).contains("Try again").contains("same stock at the same moment");
        assertThat(DbRefusal.reason(deadlock())).contains(ContentionException.MESSAGE);
    }

    @Test
    void aControlsRefusalIsARefusalAndNotContention() {
        var refusal = new DataIntegrityViolationException("x",
                new PSQLException("A document cannot be posted by its raiser.", PSQLState.CHECK_VIOLATION));
        assertThat(DbRefusal.isContention(refusal)).isFalse();
        RuntimeException asRefusal = DbRefusal.asRefusal(refusal);
        assertThat(asRefusal).isInstanceOf(ControlRefusedException.class).isNotInstanceOf(ContentionException.class);
        assertThat(DbRefusal.isContention(new RuntimeException("something else"))).isFalse();
    }
}
