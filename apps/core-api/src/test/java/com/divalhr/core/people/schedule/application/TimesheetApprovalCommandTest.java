package com.divalhr.core.people.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.people.schedule.api.ApproveTimesheetRequest;
import com.divalhr.core.people.schedule.api.ReturnTimesheetRequest;
import com.divalhr.core.people.schedule.api.SubmitTimesheetRequest;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-070B/C (T70BC-2, T70BC-6, T70BC-7): request shapes, the return reason's grammar (the same as
 * V33's {@code people.timesheet_return_reason_valid}, plus Unicode format characters), and the
 * reason entering the idempotency fingerprint only as its digest.
 */
class TimesheetApprovalCommandTest {

  private static final String KEY = "11111111-1111-4111-8111-111111111111";
  private static final String ID = "22222222-2222-4222-8222-222222222222";
  private static final String ACTION = "33333333-3333-4333-8333-333333333333";

  private static ReturnTimesheetRequest body(Object reason) {
    ReturnTimesheetRequest request = new ReturnTimesheetRequest();
    request.setRevision(1);
    request.setGenerationVersion(2);
    request.setCycle(1);
    request.setSubmissionActionId(ACTION);
    request.setReason(reason);
    request.setConfirm(true);
    return request;
  }

  private static String reason(Object raw) {
    FieldErrors errors = new FieldErrors();
    String value = TimesheetApprovalCommand.reason(raw, errors);
    return value == null ? "refused" : value;
  }

  @Test
  void theReturnReasonIsTrimmedAndInItsGrammar() {
    assertThat(reason("  Lundi sans pause déclarée.  ")).isEqualTo("Lundi sans pause déclarée.");
    assertThat(reason("x".repeat(10))).isEqualTo("x".repeat(10));
    assertThat(reason("😀".repeat(500))).isEqualTo("😀".repeat(500));
    for (Object bad :
        List.of(
            "x".repeat(9),
            "x".repeat(501),
            "   ",
            "Voir https://exemple.test ici",
            "Voir www.exemple.test ici",
            "Écrire à mailto:rh ici",
            "Balise <b>forte</b> ici",
            "Retour\u0007 avec contrôle",
            "Retour​ invisible ici",
            "Retour‮ inversé ici",
            42)) {
      assertThat(reason(bad)).as(String.valueOf(bad)).isEqualTo("refused");
    }
  }

  @Test
  void shapesAreValidatedAndAMalformedTimesheetIsTheSafe404() {
    assertThatThrownBy(() -> TimesheetApprovalCommand.giveBack(KEY, ID, body(null)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    ApproveTimesheetRequest approval = new ApproveTimesheetRequest();
    approval.setRevision("1");
    approval.setGenerationVersion(0);
    approval.setCycle(1);
    approval.setSubmissionActionId("nope");
    approval.setConfirm(true);
    assertThatThrownBy(() -> TimesheetApprovalCommand.approve(KEY, ID, approval))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    SubmitTimesheetRequest submission = new SubmitTimesheetRequest();
    submission.setRevision(1);
    submission.setGenerationVersion(1);
    submission.setConfirm(true);
    assertThatThrownBy(() -> TimesheetApprovalCommand.submit(KEY, "not-a-uuid", submission))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.TIMESHEET_TARGET_NOT_FOUND));
    assertThatThrownBy(() -> TimesheetApprovalCommand.submit(null, ID, submission))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void theReasonEntersTheFingerprintOnlyAsItsDigest() {
    TenantId tenant = new TenantId(UUID.randomUUID());
    var command = TimesheetApprovalCommand.giveBack(KEY, ID, body("Lundi sans pause déclarée."));
    assertThat(command.reason()).isEqualTo("Lundi sans pause déclarée.");
    String canonical = command.canonical(tenant).toString();
    assertThat(canonical).doesNotContain("pause").contains(command.reasonDigest());
    assertThat(command.toString()).isEqualTo("TimesheetApprovalCommand.Decide[RETURN]");
  }
}
