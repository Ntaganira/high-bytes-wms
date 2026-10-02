package heritier.ntaganira.highbytes.wms.admin.workflow;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.workflow
 * - File       : WorkflowService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The approval chains as they stand, and the one change made here: moving a switchover still to come
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditEntry;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The approval chains (FR-WF-01..03), read whole, and the one change made from a screen: when a switchover still
 * to come happens.
 *
 * <p>A chain's steps, the roles that sign them and their order change only by a reviewed migration, and never once
 * a document is bound to the chain: a document finishes under the chain it began with (V17). The System
 * Administrator, who holds {@code admin.workflow} and takes no part in operations (invariant 8), does not decide
 * who approves stock leaving. What moves here is the date one version gives way to the next, and only while that
 * date is still to come: a date that has passed is history, and a chain is never put in force for a day already
 * begun. Both chains move together, in the order that never lets them overlap, and the move is recorded with its
 * reason.
 */
@Service
@Transactional(readOnly = true)
public class WorkflowService {

    static final String ENTITY = "workflow_definition";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public WorkflowService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** One step of a chain. */
    public record ChainStep(int sequenceNo, UUID roleId, String roleName, String actionLabel,
                            boolean mandatory, boolean blocksRelease) {}

    /** One version of a document type's chain, and the documents bound to it. */
    public record ChainVersion(UUID id, int version, LocalDate effectiveFrom, LocalDate effectiveTo, String basis,
                               String notes, int boundCount, int openCount, List<ChainStep> steps, String state) {

        /** The last day it is in force, or null when it runs on. */
        public LocalDate lastDay() {
            return effectiveTo == null ? null : effectiveTo.minusDays(1);
        }

        public String label(String typeName) {
            return typeName + " v" + version;
        }
    }

    /** The day one version gives way to the next; movable while it is still to come. */
    public record Switchover(UUID endingId, int endingVersion, UUID startingId, int startingVersion,
                             LocalDate date, LocalDate earliest, LocalDate latest, boolean movable) {}

    /** A document type's chain: every version, and the switchovers between them. */
    public record Chain(String typeCode, String typeName, String formReference,
                        List<ChainVersion> versions, List<Switchover> switchovers) {}

    // ---- reads -----------------------------------------------------------

    public List<Chain> chains() {
        LocalDate today = LocalDate.now(KigaliTime.ZONE);

        Map<UUID, List<ChainStep>> steps = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT ws.workflow_definition_id, ws.sequence_no, r.id AS role_id, r.name AS role_name,
                       ws.action_label, ws.is_mandatory, ws.blocks_release
                  FROM workflow_step ws JOIN role r ON r.id = ws.required_role_id
                 ORDER BY ws.workflow_definition_id, ws.sequence_no
                """)
                .query((RowCallbackHandler) rs -> steps
                        .computeIfAbsent(rs.getObject("workflow_definition_id", UUID.class), k -> new ArrayList<>())
                        .add(new ChainStep(rs.getInt("sequence_no"), rs.getObject("role_id", UUID.class),
                                rs.getString("role_name"), rs.getString("action_label"),
                                rs.getBoolean("is_mandatory"), rs.getBoolean("blocks_release"))));

        record Row(String typeCode, String typeName, String formReference, ChainVersion version) {}
        List<Row> rows = jdbc.sql("""
                SELECT dt.code, dt.name, dt.form_reference,
                       wd.id, wd.version, wd.effective_from, wd.effective_to, wd.basis, wd.notes,
                       (SELECT COUNT(*) FROM document d WHERE d.workflow_definition_id = wd.id) AS bound,
                       (SELECT COUNT(*) FROM document d WHERE d.workflow_definition_id = wd.id
                                                         AND d.status IN ('DRAFT', 'PENDING', 'APPROVED')) AS open
                  FROM workflow_definition wd
                  JOIN document_type dt ON dt.id = wd.document_type_id
                 ORDER BY dt.sort_order, dt.code, wd.effective_from
                """)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    LocalDate from = rs.getObject("effective_from", LocalDate.class);
                    LocalDate to = rs.getObject("effective_to", LocalDate.class);
                    String state = to != null && !to.isAfter(today) ? "ENDED"
                            : from.isAfter(today) ? "SCHEDULED" : "IN_FORCE";
                    return new Row(rs.getString("code"), rs.getString("name"), rs.getString("form_reference"),
                            new ChainVersion(id, rs.getInt("version"), from, to, rs.getString("basis"),
                                    rs.getString("notes"), rs.getInt("bound"), rs.getInt("open"),
                                    steps.getOrDefault(id, List.of()), state));
                })
                .list();

        Map<String, List<Row>> byType = new LinkedHashMap<>();
        rows.forEach(r -> byType.computeIfAbsent(r.typeCode(), k -> new ArrayList<>()).add(r));

        List<Chain> chains = new ArrayList<>();
        byType.forEach((code, typeRows) -> {
            List<ChainVersion> versions = typeRows.stream().map(Row::version).toList();
            List<Switchover> switchovers = new ArrayList<>();
            for (int i = 0; i + 1 < versions.size(); i++) {
                ChainVersion a = versions.get(i);
                ChainVersion b = versions.get(i + 1);
                if (a.effectiveTo() != null && a.effectiveTo().equals(b.effectiveFrom())) {
                    // Each chain keeps at least a day; the boundary never comes before tomorrow.
                    LocalDate earliest = max(today.plusDays(1), a.effectiveFrom().plusDays(1));
                    LocalDate latest = b.effectiveTo() == null ? null : b.effectiveTo().minusDays(1);
                    switchovers.add(new Switchover(a.id(), a.version(), b.id(), b.version(), a.effectiveTo(),
                            earliest, latest, a.effectiveTo().isAfter(today)));
                }
            }
            Row first = typeRows.get(0);
            chains.add(new Chain(code, first.typeName(), first.formReference(), versions, switchovers));
        });
        return chains;
    }

    /** Every recorded change to a chain's dates, newest first. */
    public List<AuditEntry> history(int limit) {
        return audit.search(new AuditService.Query(null, null, null, ENTITY, null, null, null, null, null),
                new AuditService.Scope(true, Set.of()), limit);
    }

    // ---- the one change --------------------------------------------------

    /**
     * Moves the day the chain {@code endingId} gives way to the next version of it, to {@code newDate}: the first
     * day the next version is in force. Both must still be to come.
     */
    @Transactional
    @PreAuthorize("hasAuthority('admin.workflow')")
    public void moveSwitchover(UUID endingId, LocalDate newDate, String reason, BranchView current) {
        LocalDate today = LocalDate.now(KigaliTime.ZONE);

        record Def(UUID id, UUID typeId, String typeName, int version, LocalDate from, LocalDate to) {}
        // Both chains locked for the move: two administrators moving one switchover queue.
        Def ending = jdbc.sql("""
                SELECT wd.id, wd.document_type_id, dt.name, wd.version, wd.effective_from, wd.effective_to
                  FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
                 WHERE wd.id = :id
                   FOR UPDATE OF wd
                """)
                .param("id", endingId, Types.OTHER)
                .query((rs, n) -> new Def(rs.getObject("id", UUID.class), rs.getObject("document_type_id", UUID.class),
                        rs.getString("name"), rs.getInt("version"),
                        rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class)))
                .optional()
                .orElseThrow(() -> new ChainNotFoundException(endingId));
        String endingLabel = ending.typeName() + " v" + ending.version();

        Def starting = ending.to() == null ? null : jdbc.sql("""
                SELECT wd.id, wd.document_type_id, dt.name, wd.version, wd.effective_from, wd.effective_to
                  FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
                 WHERE wd.document_type_id = :type AND wd.effective_from = :at
                   FOR UPDATE OF wd
                """)
                .param("type", ending.typeId(), Types.OTHER)
                .param("at", ending.to())
                .query((rs, n) -> new Def(rs.getObject("id", UUID.class), rs.getObject("document_type_id", UUID.class),
                        rs.getString("name"), rs.getInt("version"),
                        rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class)))
                .optional()
                .orElse(null);
        if (starting == null) {
            throw refused(endingId, endingLabel, current, "The " + endingLabel + " chain gives way to no other"
                    + " chain, so there is no switchover to move. Whether a chain ends is decided by a reviewed migration.");
        }
        String startingLabel = starting.typeName() + " v" + starting.version();

        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw refused(endingId, endingLabel, current,
                    "Say why the switchover moves: the reason is kept with the change.");
        }
        if (newDate == null) {
            throw refused(endingId, endingLabel, current, "Choose the day the " + startingLabel + " chain comes into force.");
        }
        if (!ending.to().isAfter(today)) {
            throw refused(endingId, endingLabel, current, "The " + startingLabel + " chain has been in force since "
                    + DAY.format(ending.to()) + ". That is history: the switchover does not move.");
        }
        if (!newDate.isAfter(today)) {
            throw refused(endingId, endingLabel, current, "The " + startingLabel + " chain can come into force on "
                    + DAY.format(today.plusDays(1)) + " at the earliest: a chain is never put in force for a day already begun.");
        }
        if (!newDate.isAfter(ending.from())) {
            throw refused(endingId, endingLabel, current, "The " + endingLabel + " chain is in force from "
                    + DAY.format(ending.from()) + ", so it gives way on " + DAY.format(ending.from().plusDays(1)) + " at the earliest.");
        }
        if (starting.to() != null && !newDate.isBefore(starting.to())) {
            throw refused(endingId, endingLabel, current, "The " + startingLabel + " chain ends on "
                    + DAY.format(starting.to().minusDays(1)) + ", so it must come into force before then.");
        }
        if (newDate.equals(ending.to())) return;

        try {
            // The chain that gives ground moves first, so the two never cover the same day; the gap between the
            // two statements is judged at the end, together (V17).
            if (newDate.isAfter(ending.to())) {
                setFrom(starting.id(), newDate);
                setTo(ending.id(), newDate);
            } else {
                setTo(ending.id(), newDate);
                setFrom(starting.id(), newDate);
            }
            jdbc.sql("SET CONSTRAINTS workflow_definition_contiguous IMMEDIATE").update();
        } catch (DataAccessException e) {
            if (DbRefusal.isContention(e)) throw DbRefusal.asRefusal(e);
            throw DbRefusal.reason(e)
                    .<RuntimeException>map(r -> refused(endingId, endingLabel, current, r))
                    .orElse(e);
        }

        audit.recordInTransaction(ENTITY, ending.id(), "Approval chain · " + endingLabel, AuditAction.UPDATE,
                AuditSnapshot.of().field("Last day in force", ending.to().minusDays(1), newDate.minusDays(1)),
                current, why);
        audit.recordInTransaction(ENTITY, starting.id(), "Approval chain · " + startingLabel, AuditAction.UPDATE,
                AuditSnapshot.of().field("First day in force", ending.to(), newDate),
                current, why);
    }

    private void setFrom(UUID id, LocalDate date) {
        jdbc.sql("UPDATE workflow_definition SET effective_from = :d WHERE id = :id")
                .param("d", date).param("id", id, Types.OTHER).update();
    }

    private void setTo(UUID id, LocalDate date) {
        jdbc.sql("UPDATE workflow_definition SET effective_to = :d WHERE id = :id")
                .param("d", date).param("id", id, Types.OTHER).update();
    }

    private ControlRefusedException refused(UUID id, String label, BranchView current, String reason) {
        audit.recordRefusal(ENTITY, id, "Approval chain · " + label,
                AuditSnapshot.of().value("Attempted", "Move the switchover"), current, reason);
        return new ControlRefusedException(reason);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public static class ChainNotFoundException extends RuntimeException {
        public ChainNotFoundException(UUID id) {
            super("No approval chain with id " + id);
        }
    }
}
