package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : ReleaseGate.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The answer to "may these goods leave", as the release banner shows it
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Whether goods may leave against a delivery authorization, in one of four
 * states: {@code BLOCKED} (unsigned steps remain), {@code RELEASED} (fully
 * signed, the Internal Controller's release last), {@code DELIVERED} (the
 * delivery note is posted) and {@code VOID} (rejected or cancelled).
 *
 * <p>Every field the banner shows is here, so the fragment decides nothing:
 * which steps are unsigned, who could sign each, how long it has waited and
 * whether that is past its escalation time.
 */
public record ReleaseGate(
        String state,
        String daoSerial,
        List<Waiting> waiting,
        String releasedBy,
        LocalDateTime releasedAt,
        UUID dnId,
        String dnSerial,
        LocalDateTime deliveredAt,
        String deliveredBy,
        String note,
        String kind
) {

    /** A delivery authorization's gate: the wording is about loading and delivery. */
    public ReleaseGate(String state, String daoSerial, List<Waiting> waiting, String releasedBy,
                       LocalDateTime releasedAt, UUID dnId, String dnSerial, LocalDateTime deliveredAt,
                       String deliveredBy, String note) {
        this(state, daoSerial, waiting, releasedBy, releasedAt, dnId, dnSerial, deliveredAt, deliveredBy, note, "DAO");
    }

    public String getKind() { return kind; }

    /**
     * An unsigned step. {@code hoursWaiting} is null for a step not yet
     * reached (nobody is waiting on it); {@code overdue} is true once the wait
     * passes the step's {@code escalate_after_hours}.
     */
    public record Waiting(int sequenceNo, String actionLabel, String roleName, List<String> holders,
                          Long hoursWaiting, Integer escalateAfterHours, boolean overdue, boolean current) {

        public int getSequenceNo()          { return sequenceNo; }
        public String getActionLabel()      { return actionLabel; }
        public String getRoleName()         { return roleName; }
        public List<String> getHolders()    { return holders; }
        public Long getHoursWaiting()       { return hoursWaiting; }
        public Integer getEscalateAfterHours() { return escalateAfterHours; }
        public boolean isOverdue()          { return overdue; }
        public boolean isCurrent()          { return current; }
    }

    public boolean released()  { return "RELEASED".equals(state); }
    public boolean blocked()   { return "BLOCKED".equals(state) || "VOID".equals(state); }

    public String getState()          { return state; }
    public String getDaoSerial()      { return daoSerial; }
    public List<Waiting> getWaiting() { return waiting; }
    public String getReleasedBy()     { return releasedBy; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public UUID getDnId()             { return dnId; }
    public String getDnSerial()       { return dnSerial; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public String getDeliveredBy()    { return deliveredBy; }
    public String getNote()           { return note; }
}
