package com.divalhr.core.platform.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.CreateInvitationService;
import com.divalhr.core.identity.application.InvitationAcceptance;
import com.divalhr.core.identity.application.InvitationQueryService;
import com.divalhr.core.identity.application.PublicInvitationService;
import com.divalhr.core.identity.application.ResendInvitationService;
import com.divalhr.core.identity.application.RevokeInvitationService;
import com.divalhr.core.tenant.api.CostCenterController;
import com.divalhr.core.tenant.api.DepartmentController;
import com.divalhr.core.tenant.application.AssignSiteRegionService;
import com.divalhr.core.tenant.application.CreateLegalEntityService;
import com.divalhr.core.tenant.application.CreateOrganizationService;
import com.divalhr.core.tenant.application.CreateRegionService;
import com.divalhr.core.tenant.application.CreateSiteService;
import com.divalhr.core.tenant.application.CreateTeamService;
import com.divalhr.core.tenant.application.HierarchyQueryService;
import com.divalhr.core.tenant.application.TeamQueryService;
import com.divalhr.core.tenant.domain.SiteUnitKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OperationNameTest {

  /** Every operation and audit-action name the application uses today. */
  static final List<String> CURRENT =
      List.of(
          CreateOrganizationService.OPERATION,
          CreateLegalEntityService.OPERATION,
          CreateSiteService.OPERATION,
          HierarchyQueryService.LIST_LEGAL_ENTITIES,
          HierarchyQueryService.LIST_SITES,
          DepartmentController.CREATE,
          DepartmentController.LIST,
          CostCenterController.CREATE,
          CostCenterController.LIST,
          CreateRegionService.OPERATION,
          HierarchyQueryService.LIST_REGIONS,
          AssignSiteRegionService.OPERATION,
          CreateTeamService.OPERATION,
          TeamQueryService.LIST_TEAMS,
          CreateInvitationService.OPERATION,
          ResendInvitationService.OPERATION,
          RevokeInvitationService.OPERATION,
          InvitationQueryService.LIST_INVITATIONS,
          PublicInvitationService.INSPECT,
          InvitationAcceptance.OPERATION,
          "invitation.expire");

  /** Malformed names from Issue #17 plus the grammar's edges. */
  static final List<String> MALFORMED =
      List.of("a.-", "a.b-", "a.-b", "a..b", "a.b--c", "-a.b", "A.b", "a", "x.---", "a.b.", "");

  @Test
  void acceptsEveryCurrentNameAndRejectsMalformedOnes() {
    assertThat(CURRENT).allMatch(OperationName::isValid);
    assertThat(MALFORMED).noneMatch(OperationName::isValid);
    assertThat(OperationName.isValid(null)).isFalse();
  }

  @Test
  void controllerConstantsMatchTheDomainSemantics() {
    assertThat(DepartmentController.CREATE).isEqualTo(SiteUnitKind.DEPARTMENT.createOperation());
    assertThat(DepartmentController.LIST).isEqualTo(SiteUnitKind.DEPARTMENT.listOperation());
    assertThat(CostCenterController.CREATE).isEqualTo(SiteUnitKind.COST_CENTER.createOperation());
    assertThat(CostCenterController.LIST).isEqualTo(SiteUnitKind.COST_CENTER.listOperation());
    assertThat(SiteUnitKind.DEPARTMENT.createdEventType())
        .isEqualTo("tenant.department-created.v1");
    assertThat(SiteUnitKind.COST_CENTER.createdEventType())
        .isEqualTo("tenant.cost-center-created.v1");
    assertThat(CreateRegionService.OPERATION).isEqualTo("region.create");
    assertThat(HierarchyQueryService.LIST_REGIONS).isEqualTo("region.list");
    assertThat(AssignSiteRegionService.OPERATION).isEqualTo("site.region.assign");
    assertThat(CreateRegionService.EVENT_TYPE).isEqualTo("tenant.region-created.v1");
    assertThat(AssignSiteRegionService.EVENT_TYPE).isEqualTo("tenant.site-region-assigned.v1");
    assertThat(CreateTeamService.OPERATION).isEqualTo("team.create");
    assertThat(TeamQueryService.LIST_TEAMS).isEqualTo("team.list");
    assertThat(CreateTeamService.EVENT_TYPE).isEqualTo("tenant.team-created.v1");
    assertThat(CreateInvitationService.OPERATION).isEqualTo("invitation.create");
    assertThat(ResendInvitationService.OPERATION).isEqualTo("invitation.resend");
    assertThat(RevokeInvitationService.OPERATION).isEqualTo("invitation.revoke");
    assertThat(InvitationQueryService.LIST_INVITATIONS).isEqualTo("invitation.list");
    assertThat(PublicInvitationService.INSPECT).isEqualTo("invitation.inspect");
    assertThat(InvitationAcceptance.OPERATION).isEqualTo("invitation.accept");
  }

  @Test
  void requireNeverEchoesTheRejectedValue() {
    assertThatThrownBy(() -> OperationName.require("a..secret", "audit action"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret");
  }

  @Test
  void v4MigrationUsesExactlyTheSharedGrammar() throws IOException {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/migration/V4__tighten_operation_name_checks.sql"));
    // Two pre-flight predicates and two constraints, all the same literal.
    assertThat(sql.split("'" + java.util.regex.Pattern.quote(OperationName.GRAMMAR) + "'", -1))
        .hasSize(5);
  }

  @Test
  void theGrammarIsDefinedOnlyOnce() throws IOException {
    String fragment = "(\\\\.[a-z]+(-[a-z]+)*)+$";
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      List<String> holders =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> read(path).contains(fragment))
              .map(Path::toString)
              .toList();
      assertThat(holders).singleElement().asString().endsWith("OperationName.java");
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
