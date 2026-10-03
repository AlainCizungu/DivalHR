package db.migration;

import com.divalhr.core.people.domain.EmployeeSearchKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * MVP-021 (H16): fills {@code people.employee.search_key} for existing employees with the same
 * normalizer the application uses for every later write ({@link EmployeeSearchKey}), so the stored
 * keys and search queries always agree. V14 adds the column; V14_2 makes it mandatory. Names are
 * read and written inside the migration transaction only; nothing is logged.
 */
public class V14_1__Employee_search_key extends BaseJavaMigration {

  private static final int BATCH = 500;

  /** One employee's names. */
  private record Names(UUID id, String givenNames, String familyName) {}

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    List<Names> pending = new ArrayList<>();
    try (Statement select = connection.createStatement();
        ResultSet rows =
            select.executeQuery(
                "SELECT id, given_names, family_name FROM people.employee"
                    + " WHERE search_key IS NULL ORDER BY id")) {
      while (rows.next()) {
        pending.add(new Names(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3)));
      }
    }
    try (PreparedStatement update =
        connection.prepareStatement("UPDATE people.employee SET search_key = ? WHERE id = ?")) {
      int batched = 0;
      for (Names names : pending) {
        update.setString(1, EmployeeSearchKey.of(names.givenNames(), names.familyName()));
        update.setObject(2, names.id());
        update.addBatch();
        if (++batched == BATCH) {
          update.executeBatch();
          batched = 0;
        }
      }
      if (batched > 0) {
        update.executeBatch();
      }
    }
  }
}
