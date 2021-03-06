package com.yukthi.persistence.repository.executors;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.yukthi.persistence.FieldDetails;
import com.yukthi.persistence.ICrudRepository;
import com.yukthi.persistence.InvalidMappingException;
import com.yukthi.persistence.repository.InvalidRepositoryException;
import com.yukthi.persistence.repository.annotations.ExtendedFieldNames;
import com.yukthi.persistence.repository.annotations.Field;
import com.yukthi.persistence.repository.annotations.OrderBy;
import com.yukthi.persistence.repository.annotations.OrderByField;
import com.yukthi.persistence.repository.annotations.OrderByType;
import com.yukthi.persistence.repository.annotations.ResultMapping;
import com.yukthi.persistence.repository.annotations.SearchResult;
import com.yukthi.persistence.repository.search.IDynamicSearchResult;
import com.yukthi.utils.annotations.RecursiveAnnotationFactory;
import com.yukthi.utils.exceptions.InvalidConfigurationException;

/**
 * Provides common base functionality for search query type executors - Finder and Search queries
 * @author akiran
 */
public abstract class AbstractSearchQuery extends QueryExecutor
{
	private static Logger logger = LogManager.getLogger(AbstractSearchQuery.class);
	
	private static RecursiveAnnotationFactory recursiveAnnotationFactory = new RecursiveAnnotationFactory();
	
	protected Class<?> returnType;
	protected Class<?> collectionReturnType = null;

	/**
	 * Keeps track of different parts required by query
	 */
	protected ConditionQueryBuilder conditionQueryBuilder;
	
	/**
	 * Description of the method
	 */
	protected String methodDesc;
	
	/**
	 * Fetches result field from return object type
	 * @param returnType
	 * @param query
	 * @param index
	 */
	private void fetchResultFieldsFromObject(Class<?> returnType)
	{
		java.lang.reflect.Field fields[] = returnType.getDeclaredFields();
		Field resultField = null;
		String name = null;
		
		//loop through query object type fields 
		for(java.lang.reflect.Field objField : fields)
		{
			resultField = objField.getAnnotation(Field.class);
			
			//if field is not marked as condition
			if(resultField == null)
			{
				continue;
			}
			
			//fetch entity field name
			name = resultField.value();
			
			//if name is not specified in condition
			if(name.trim().length() == 0)
			{
				//use field name
				name = objField.getName();
			}
			
			conditionQueryBuilder.addResultField(objField.getName(), objField.getType(), name, methodDesc);
		}
	}
	
	/**
	 * Fetches/populates entity fields as result fields
	 */
	private void fetchEntityResultFields()
	{
		logger.trace("Started method: setFullEntityDetails");
		
		//loop through entity details
		for(FieldDetails field: entityDetails.getFieldDetails())
		{
			//if the field is not owned by this table
			if(!field.isTableOwned())
			{
				continue;
			}

			//adds the current field as result field
			conditionQueryBuilder.addResultField(field.getName(), field.getField().getType(), field.getName(), methodDesc);
		}
		
		this.returnType = entityDetails.getEntityType();
	}
	
	protected void fetchReturnDetails(Method method)
	{
		logger.trace("Started method: fetchReturnDetails");
		
		this.returnType = method.getReturnType();
		
		if(void.class.equals(this.returnType))
		{
			throw new InvalidRepositoryException("Found void finder method '" + method.getName() + "' in repository: " + repositoryType.getName());
		}
		
		//TODO: Support map types
		if(Collection.class.isAssignableFrom(returnType))
		{
			if(returnType.isAssignableFrom(ArrayList.class))
			{
				this.collectionReturnType = ArrayList.class;
			}
			else if(returnType.isAssignableFrom(HashSet.class))
			{
				this.collectionReturnType = HashSet.class;
			}
			else
			{
				try
				{
					returnType.newInstance();
					this.collectionReturnType = returnType;
				}catch(Exception ex)
				{
					throw new InvalidRepositoryException("Unsupported collection return type found on finder '" 
								+ method.getName() + "' of repository: " + repositoryType.getName());
				}
			}
			
			ParameterizedType type = (ParameterizedType)method.getGenericReturnType();
			Type typeArgs[] = type.getActualTypeArguments();
			
			if(typeArgs.length != 1)
			{
				throw new InvalidRepositoryException("Unsupported collection return type (with mutliple type params) found on finder '" 
							+ method.getName() + "' of repository: " + repositoryType.getName());
			}

			this.returnType = TypeUtils.getRawType(typeArgs[0], repositoryType);
		}
		else
		{
			this.returnType = TypeUtils.getRawType(method.getGenericReturnType(), repositoryType);
		}
		
		SearchResult searchResult = recursiveAnnotationFactory.findAnnotationRecursively(method, SearchResult.class);
		
		//if return type matches with entity type, add all entity fields as result fields
		if(entityDetails.getEntityType().equals(this.returnType) || ICrudRepository.class.equals(method.getDeclaringClass()))
		{
			fetchEntityResultFields();
		}
		//if method is annotated with Field annotation use that only as return field
		else if(method.getAnnotation(Field.class) != null)
		{
			Field field = method.getAnnotation(Field.class);
			conditionQueryBuilder.addResultField(null, this.returnType, field.value(), methodDesc);
		}
		else if(searchResult != null)
		{
			ResultMapping mappings[] = searchResult.mappings();

			//if mappings are specified fetch field details from bean fields
			if(mappings == null || mappings.length == 0)
			{
				fetchResultFieldsFromObject(returnType);
			}
			//if mappings are specified, add specified mappings to query-builder
			else
			{
				try
				{
					PropertyDescriptor propertyDescriptor = null;
					Object returnSampleBean = this.returnType.newInstance();
					
					for(ResultMapping mapping : mappings)
					{
						propertyDescriptor = PropertyUtils.getPropertyDescriptor(returnSampleBean, mapping.property());
						conditionQueryBuilder.addResultField(mapping.property(), propertyDescriptor.getPropertyType(), mapping.entityField(), methodDesc);
					}
				}catch(Exception ex)
				{
					throw new InvalidMappingException("An error occurred while parsing @SearchResult mappings of " + methodDesc, ex);
				}
			}
		}
		else
		{
			throw new UnsupportedOperationException("Failed to determine return details of finder method: " + methodDesc);
		}
	}
	
	protected void fetchOrderDetails(Method method)
	{
		OrderBy orderBy = recursiveAnnotationFactory.findAnnotationRecursively(method, OrderBy.class);
		
		if(orderBy == null)
		{
			return;
		}
		
		if(orderBy.fields().length > 0)
		{
			for(OrderByField field : orderBy.fields())
			{
				if(!entityDetails.hasField(field.name()))
				{
					throw new InvalidMappingException("Invalid field '" + field.name() + "' specified in @OrderBy annotation of finder method - " + methodDesc);
				}
				
				conditionQueryBuilder.addOrderByField(field.name(), field.type(), methodDesc);
			}
		}
		else
		{
			for(String field : orderBy.value())
			{
				if(!entityDetails.hasField(field))
				{
					throw new InvalidMappingException("Invalid field '" + field + "' specified in @OrderBy annotation of finder method - " + methodDesc);
				}
				
				conditionQueryBuilder.addOrderByField(field, OrderByType.ASC, methodDesc);
			}
		}
	}
	
	/**
	 * Fetches param index which provides custom fields to fetch.
	 * @param method Method from which param needs to be fetched.
	 * @return index at which param names might have specified.
	 */
	protected int getExtendedFieldParam(Method method)
	{
		//if extended table is not present return
		if(entityDetails.getExtendedTableDetails() == null)
		{
			return -1;
		}
		
		//if return type is not entity type and not dynamic search result, ignore extension field names, if any
		if(!returnType.equals(entityDetails.getEntityType()) && !IDynamicSearchResult.class.isAssignableFrom(returnType))
		{
			return -1;
		}
		
		Parameter params[] = method.getParameters();
		
		if(params == null || params.length == 0)
		{
			return -1;
		}
		
		ParameterizedType parameterizedType = null;
		
		for(int i = 0; i < params.length; i++)
		{
			if(params[i].getAnnotation(ExtendedFieldNames.class) != null)
			{
				if(!Collection.class.isAssignableFrom(params[i].getType()))
				{
					throw new InvalidConfigurationException("Non collection param at index {} is declared as extended-field-names - {}", i, methodDesc);
				}
				
				parameterizedType = (ParameterizedType) params[i].getParameterizedType();
				
				if(!String.class.equals(parameterizedType.getActualTypeArguments()[0]))
				{
					throw new InvalidConfigurationException("Non String collection param at index {} is declared as extended-field-names - {}", i, methodDesc);
				}
				
				return i;
			}
		}
		
		return -1;
	}

}
