package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit records and internal outbox events of accounting mappings and journals (MVP-096, ADR 0039
 * section 10). Metadata and event data are an allow-list ({@link #ALLOWED}): identifiers, versions,
 * the currency, the line count and the kind of view. They never carry an amount, a total, an
 * account code, a digest or an employee; a key outside the allow-list is refused before anything is
 * written. The two events are internal: no webhook or WhatsApp type maps them.
 */
@Component
public class AccountingRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit action of a mapping preview (disclosure). */
  public static final String MAPPING_PREVIEW = "accounting-mapping.preview";

  /** Audit action of an appended mapping version. */
  public static final String MAPPING_CREATE = "accounting-mapping.create";

  /** Audit action of a mapping list read (disclosure). */
  public static final String MAPPING_READ = "accounting-mapping.read";

  /** Audit action of a journal preview (disclosure). */
  public static final String JOURNAL_PREVIEW = "accounting-journal.preview";

  /** Audit action of a generated journal. */
  public static final String JOURNAL_GENERATE = "accounting-journal.generate";

  /** Audit action of a journal read or list (disclosure). */
  public static final String JOURNAL_READ = "accounting-journal.read";

  /** Audit action of a CSV or manifest download (disclosure). */
  public static final String JOURNAL_DOWNLOAD = "accounting-journal.download";

  /** Internal event of an appended mapping version. */
  public static final String MAPPING_CREATED_EVENT = "payroll.accounting-mapping-created.v1";

  /** Internal event of a generated journal. */
  public static final String JOURNAL_GENERATED_EVENT = "payroll.accounting-journal-generated.v1";

  /** The only keys audit metadata and event data may carry. */
  public static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "legalEntityId",
          "packageId",
          "mappingId",
          "journalId",
          "currency",
          "version",
          "lineCount",
          "view",
          "resultCount");

  private static final String SOURCE = "core-api/payroll";
  private static final String MAPPING_RESOURCE = "accounting-mapping";
  private static final String JOURNAL_RESOURCE = "accounting-journal";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public AccountingRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * An appended mapping version, without any account code.
   *
   * @param legalEntityId legal entity
   * @param mappingId the new version's row
   * @param currency currency
   * @param version version number
   * @param at server instant
   */
  public record MappingChange(
      UUID legalEntityId, UUID mappingId, String currency, int version, Instant at) {
    @Override
    public String toString() {
      return "AccountingMappingChange[" + mappingId + "]";
    }
  }

  /**
   * A generated journal, without any amount, total or account code.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param journalId the new journal
   * @param currency currency
   * @param version journal version
   * @param lineCount lines
   * @param at server instant
   */
  public record JournalChange(
      UUID legalEntityId,
      UUID packageId,
      UUID journalId,
      String currency,
      int version,
      int lineCount,
      Instant at) {
    @Override
    public String toString() {
      return "AccountingJournalChange[" + journalId + "]";
    }
  }

  /**
   * The fail-closed disclosure audit of a preview, a read or a download: the view, identifiers and
   * a count only.
   *
   * @param caller verified caller
   * @param action one of the preview, read or download actions
   * @param resourceId the mapping chain's legal entity, the package or the journal
   * @param ids identifiers, keys from {@link #ALLOWED} only
   * @param view the kind of view
   * @param count rows disclosed
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void disclosed(
      PayrollCaller caller,
      String action,
      UUID resourceId,
      Map<String, String> ids,
      String view,
      int count,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.putAll(ids);
    metadata.put("view", view);
    metadata.put("resultCount", count);
    requireAllowed(metadata);
    String resource =
        action.startsWith("accounting-mapping.") ? MAPPING_RESOURCE : JOURNAL_RESOURCE;
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            resource,
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-ACCOUNTING-DISCLOSURE\nversion=1\naction="
                    + action
                    + "\nview="
                    + view
                    + "\nresource="
                    + resourceId
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  /**
   * Records an appended mapping version: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param change the appended version
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mappingCreated(PayrollCaller caller, MappingChange change) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", SCHEMA_VERSION);
    data.put("legalEntityId", change.legalEntityId().toString());
    data.put("mappingId", change.mappingId().toString());
    data.put("currency", change.currency());
    data.put("version", change.version());
    requireAllowed(data);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            change.at(),
            caller.subject(),
            MAPPING_CREATE,
            MAPPING_RESOURCE,
            change.mappingId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            data,
            Fingerprints.sha256(
                "DIVALHR-ACCOUNTING-MAPPING-CREATED\nversion=1\nmapping="
                    + change.mappingId()
                    + "\nsequence="
                    + change.version()
                    + "\n")));
    outbox.append(
        envelope(caller, MAPPING_CREATED_EVENT, change.mappingId(), change.at(), data));
  }

  /**
   * Records a generated journal: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param change the generated journal
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void journalGenerated(PayrollCaller caller, JournalChange change) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", SCHEMA_VERSION);
    data.put("legalEntityId", change.legalEntityId().toString());
    data.put("packageId", change.packageId().toString());
    data.put("journalId", change.journalId().toString());
    data.put("version", change.version());
    data.put("currency", change.currency());
    data.put("lineCount", change.lineCount());
    requireAllowed(data);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            change.at(),
            caller.subject(),
            JOURNAL_GENERATE,
            JOURNAL_RESOURCE,
            change.journalId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            data,
            Fingerprints.sha256(
                "DIVALHR-ACCOUNTING-JOURNAL-GENERATED\nversion=1\njournal="
                    + change.journalId()
                    + "\npackage="
                    + change.packageId()
                    + "\n")));
    outbox.append(
        envelope(caller, JOURNAL_GENERATED_EVENT, change.journalId(), change.at(), data));
  }

  private static void requireAllowed(Map<String, Object> metadata) {
    for (String key : metadata.keySet()) {
      if (!ALLOWED.contains(key)) {
        throw new IllegalArgumentException("accounting metadata key outside the allow-list");
      }
    }
  }

  private static EventEnvelope envelope(
      PayrollCaller caller, String type, UUID subject, Instant at, Map<String, Object> data) {
    return new EventEnvelope(
        UUID.randomUUID().toString(),
        type,
        SCHEMA_VERSION,
        caller.tenant().toString(),
        SOURCE,
        subject.toString(),
        at.toString(),
        caller.correlationId(),
        null,
        data);
  }
}
