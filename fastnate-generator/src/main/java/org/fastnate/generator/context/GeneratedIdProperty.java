package org.fastnate.generator.context;

import java.io.IOException;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import org.fastnate.generator.statements.ColumnExpression;
import org.fastnate.generator.statements.PrimitiveColumnExpression;
import org.fastnate.generator.statements.StatementsWriter;
import org.fastnate.generator.statements.TableStatement;
import org.fastnate.util.ClassUtil;

import lombok.Getter;

/**
 * Describes an {@link Id} property of an {@link EntityClass}.
 *
 * @author Tobias Liefke
 * @param <E>
 *            The type of the container class
 * @param <T>
 *            The type of the property
 */
@Getter
public class GeneratedIdProperty<E, T extends Number> extends PrimitiveProperty<E, T> {

	/** Shortcut for the inverse of {@link GeneratorContext#isWriteRelativeIds()}. */
	private final boolean absoluteIds;

	/** Indicates that the ID is never "null", because we have a primitive ID value. */
	private final boolean primitive;

	/** The entity class that contains our property. */
	private final EntityClass<E> entityClass;

	/** The type of the numbers. */
	private final Class<T> type;

	/** Marker for the ID of an entity which needs to be referenced by its unique properties. */
	private final T unknownIdMarker;

	/** The generator used for generating new values of this property. */
	private final IdGenerator generator;

	/**
	 * Creates a new instance of {@link GeneratedIdProperty}.
	 *
	 * @param entityClass
	 *            the entity class
	 * @param attribute
	 *            the accessor of the id attribute
	 * @param column
	 *            the column annotation
	 */
	public GeneratedIdProperty(final EntityClass<E> entityClass, final AttributeAccessor attribute,
			final Column column) {
		super(entityClass.getContext(), entityClass.getTable(), attribute, column,
				entityClass.getContext().isWriteRelativeIds()
						|| !entityClass.getContext().getDialect().isSettingIdentityAllowed());
		this.entityClass = entityClass;
		this.absoluteIds = !getColumn().isAutoGenerated();
		this.type = (Class<T>) attribute.getType();
		this.unknownIdMarker = ClassUtil.convertNumber(-1, this.type);
		this.primitive = this.type.isPrimitive();
		this.generator = entityClass.getContext().getGenerator(attribute.getAnnotation(GeneratedValue.class),
				getTable(), getColumn());
	}

	@Override
	public void addInsertExpression(final TableStatement statement, final E entity) {
		ensureIsNew(entity);
		if (this.absoluteIds) {
			// If we generate explict IDs, lets do that now
			final T id = this.generator.createNextValue(this.type);
			setValue(entity, id);
			statement.setColumnValue(getColumn(), PrimitiveColumnExpression.create(id, getDialect()));
		} else if (!this.generator.isPostIncrement()) {
			final T id = this.generator.createNextValue(this.type);
			setValue(entity, id);
			this.generator.addNextValue(statement, getColumn(), id);
		}
	}

	@Override
	public void createPreInsertStatements(final StatementsWriter writer, final E entity) throws IOException {
		if (!this.absoluteIds) {
			this.generator.createPreInsertStatements(writer);
		}
	}

	private void ensureIsNew(final E entity) {
		if (!isNew(entity)) {
			throw new IllegalArgumentException("Tried to create entity twice: " + entity);
		}
	}

	/**
	 * Creates the reference of an entity in SQL using its (relative or absolute) id.
	 *
	 * @param entity
	 *            the entity
	 * @param whereExpression
	 *            indicates that the reference is used in a "where" statement
	 * @return the expression for the ID of that entity or {@code null} if the entity was not written up to now
	 * @throws IllegalArgumentException
	 *             if the entity is a {@link #isReference(Object) reference} without any id
	 */
	@Override
	public ColumnExpression getExpression(final E entity, final boolean whereExpression) {
		final Number targetId = getValue(entity);
		if (targetId == null) {
			return null;
		}
		if (targetId == this.unknownIdMarker) {
			throw new IllegalArgumentException("Entity must be referenced by an unique property: " + entity);
		}
		if (targetId.longValue() < 0) {
			return PrimitiveColumnExpression.create(-1 - 1 - targetId.longValue(), getDialect());
		}

		if (this.absoluteIds) {
			return PrimitiveColumnExpression.create(targetId, getDialect());
		}

		return this.generator.getExpression(getTable(), getColumn(), targetId, whereExpression);
	}

	/**
	 * Indicates that the given entity needs to be written.
	 *
	 * @param entity
	 *            the entity to check
	 * @return {@code true} if the id of the given entity is not set up to now
	 */
	public boolean isNew(final E entity) {
		final Number id = getValue(entity);
		return id == null || this.primitive && id.longValue() == 0;
	}

	/**
	 * Indicates that the given entity was not written before, but exists already in the database.
	 *
	 * @param entity
	 *            the entity to check
	 * @return {@code true} if the entity was {@link #markReference(Object) marked as reference}
	 */
	public boolean isReference(final E entity) {
		final Number id = getValue(entity);
		return id != null && id.longValue() < 0;
	}

	/**
	 * Marks an entity as reference, where we don't know the ID database.
	 *
	 * A reference is not written during SQL generation, because it exists already in the database when the script is
	 * executed. As we don't know the ID during build time we have to reference it using some unique properties.
	 *
	 * @param entity
	 *            the entity to mark
	 */
	public void markReference(final E entity) {
		if (isNew(entity)) {
			setValue(entity, this.unknownIdMarker);
		}
	}

	/**
	 * Marks an entity as reference, where we know the id in the database.
	 *
	 * A reference is not written during SQL generation, because it exists already in the database when the script is
	 * executed.
	 *
	 * @param entity
	 *            the entity to mark
	 * @param id
	 *            the id of the entity in the database
	 */
	public void markReference(final E entity, final T id) {
		if (isNew(entity)) {
			setValue(entity, ClassUtil.convertNumber(Long.valueOf(-1 - 1 - id.longValue()), this.type));
		}
	}

	/**
	 * Called after the insert statement was written, to update any nessecary state in the context.
	 *
	 * @param entity
	 *            the current entity
	 */
	public void postInsert(final E entity) {
		if (!this.absoluteIds && this.generator.isPostIncrement()) {
			// We have an identity column -> the database increments the ID after the insert
			setValue(entity, this.generator.createNextValue(this.type));
		}
	}

}
