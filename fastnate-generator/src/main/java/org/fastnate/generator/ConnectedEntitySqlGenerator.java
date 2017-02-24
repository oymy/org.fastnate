package org.fastnate.generator;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.fastnate.generator.context.ContextModelListener;
import org.fastnate.generator.context.EntityClass;
import org.fastnate.generator.context.GeneratorContext;
import org.fastnate.generator.context.IdGenerator;
import org.fastnate.generator.statements.EntityStatement;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * An extension of an {@link EntitySqlGenerator} which writes the SQL statements directly to a database connection.
 *
 * @author Tobias Liefke
 */
@Slf4j
public class ConnectedEntitySqlGenerator extends EntitySqlGenerator {

	/** The count of milliseconds to wait, until a log message with the current count of statements is written. */
	private static final long MILLISECONDS_BETWEEN_LOG_MESSAGES = 60 * 1000;

	private static String trimStatement(final String sql) {
		// Remove surrounding whitespaces and ';' - except for "end" keyword.
		int start = 0;
		int end = sql.length();
		while (start < end) {
			final char c = sql.charAt(start);
			if (c != ';' && !Character.isWhitespace(c)) {
				break;
			}
			start++;
		}
		while (start < end) {
			final char c = sql.charAt(end - 1);
			if (c == ';') {
				final String endKeyword = "end;";
				if (sql.regionMatches(true, end - endKeyword.length(), endKeyword, 0, endKeyword.length())) {
					break;
				}
			} else if (!Character.isWhitespace(c)) {
				break;
			}
			end--;
		}
		return sql.substring(start, end);
	}

	/** The database connection that was used when creating this generator. */
	@Getter
	private final Connection connection;

	/** Used to execute all SQL statements. */
	private final Statement statement;

	/** The last time that we have written a log message about the count . */
	private long lastLogTime;

	/** The count of statements that we executed up to now. */
	@Getter
	private long statementsCount;

	/**
	 * Creates a new generator that writes to a database connection.
	 *
	 * @param connection
	 *            the database connection
	 * @throws SQLException
	 *             if the connection is invalid
	 */
	public ConnectedEntitySqlGenerator(final Connection connection) throws SQLException {
		this(connection, new GeneratorContext());
	}

	/**
	 * Creates a new generator that writes to a database connection.
	 *
	 * @param connection
	 *            the database connection
	 * @param context
	 *            used to keep the state of indices and to store any configuration.
	 * @throws SQLException
	 *             if the connection is invalid
	 */
	public ConnectedEntitySqlGenerator(final Connection connection, final GeneratorContext context)
			throws SQLException {
		super(context);
		this.connection = connection;
		final Statement sharedStatement = connection.createStatement();
		this.statement = sharedStatement;

		context.addContextModelListener(new ContextModelListener() {

			@Override
			public void foundEntityClass(final EntityClass<?> entityClass) {
				// Nothing to do
			}

			@Override
			public void foundGenerator(final IdGenerator generator) {
				// Initialize generator, if necessary
				if (!context.isWriteRelativeIds()) {
					String sql = generator.getExpression(null, null, generator.getCurrentValue(), false);
					if (sql.matches("(SELECT\\W.*)")) {
						sql = sql.substring(1, sql.length() - 1);
					} else {
						sql = "SELECT (" + sql + ") currentValue " + context.getDialect().getOptionalTable();
					}
					try (ResultSet resultSet = sharedStatement.executeQuery(sql)) {
						if (resultSet.next()) {
							final long currentValue = resultSet.getLong(1);
							if (!resultSet.wasNull()) {
								generator.setCurrentValue(currentValue);
							}
						}
					} catch (final SQLException e) {
						throw new IllegalStateException("Can't initialize generator with " + sql, e);
					}
				}
			}
		});
	}

	@Override
	public void close() throws IOException {
		log.info("{} SQL statements successfully executed", this.statementsCount);
		super.close();
		try {
			this.statement.close();
		} catch (final SQLException e) {
			// Ignore - as this will only happen, if there was already an exception during update
		}
	}

	@Override
	public void writeComment(final String comment) throws IOException {
		// Ignore, as the database is not interested in comments
	}

	@Override
	public void writeSectionSeparator() throws IOException {
		// Ignore, as nothing happens in the database
	}

	@Override
	public void writeStatement(final EntityStatement stmt) throws IOException {
		final String sql = trimStatement(getContext().getDialect().createSql(stmt));
		try {
			this.statement.executeUpdate(sql);
		} catch (final SQLException e) {
			throw new IOException("Could not execute statement: " + sql, e);
		}
		this.statementsCount++;
		final long currentTime = System.currentTimeMillis();
		if (currentTime - this.lastLogTime >= MILLISECONDS_BETWEEN_LOG_MESSAGES) {
			this.lastLogTime = currentTime;
			if (this.statementsCount > 1) {
				log.info("{} SQL statements executed", this.statementsCount);
			}
		}
	}

}
