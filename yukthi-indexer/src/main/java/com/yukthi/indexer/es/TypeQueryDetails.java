package com.yukthi.indexer.es;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.yukthi.indexer.IndexType;
import com.yukthi.indexer.search.ConditionOperator;
import com.yukthi.indexer.search.JoinOperator;
import com.yukthi.indexer.search.NullCheck;
import com.yukthi.indexer.search.SearchCondition;
import com.yukthi.indexer.search.SearchQuery;
import com.yukthi.indexer.search.Subquery;
import com.yukthi.utils.CommonUtils;
import com.yukthi.utils.beans.BeanProperty;
import com.yukthi.utils.exceptions.InvalidArgumentException;
import com.yukthi.utils.exceptions.InvalidStateException;

public class TypeQueryDetails
{
	private class Condition
	{
		private String field;
		private ConditionOperator conditionOperator;
		private int boost = 0;
		private String minMatch;
		
		private boolean nullCheck = false;
		private boolean notNullCheck = false;
		
		private BeanProperty beanProperty;

		public Condition(String field, BeanProperty beanProperty, SearchCondition condition)
		{
			this.field = field;
			this.conditionOperator = condition.op();
			this.boost = condition.boost();
			this.beanProperty = beanProperty;
			
			this.minMatch = condition.minMatch();
			this.minMatch = StringUtils.isBlank(minMatch) ? null : minMatch.trim();
		}

		public Condition(String field, boolean nullCheck, boolean notNullCheck, int boost)
		{
			this.field = field;
			this.nullCheck = nullCheck;
			this.notNullCheck = notNullCheck;
			this.boost = boost;
		}
		
		public Map<String, Object> toQuery(Object queryObj, TypeIndexDetails indexDetails)
		{
			TypeIndexDetails.FieldIndexDetails fieldDet = indexDetails.getField(field);
			
			if(fieldDet == null)
			{
				throw new InvalidStateException("Invalid index field name '{}' specified in search query property - {}", field, beanProperty.getName());
			}
			
			if(nullCheck)
			{
				return CommonUtils.toMap("missing",
						CommonUtils.toMap("field", field)
					);
			}
			
			if(notNullCheck)
			{
				return CommonUtils.toMap("exists",
						CommonUtils.toMap("field", field)
					);
			}

			Object value = beanProperty.getValue(queryObj);
			
			if(value == null)
			{
				return null;
			}
			
			if(fieldDet.getEsDataType() == EsDataType.STRING && fieldDet.isIgnoreCase())
			{
				value = IndexUtils.toLowerCase(value);
			}
			
			if(fieldDet.getEsDataType() != EsDataType.STRING || fieldDet.getIndexType() == IndexType.NOT_ANALYZED)
			{
				//perform exact value search
				if(conditionOperator == ConditionOperator.EQ)
				{
					if(value instanceof Collection)
					{
						return CommonUtils.toMap("terms", 
									CommonUtils.toMap(field, value)
								);
					}
					
					return CommonUtils.toMap("term", 
								CommonUtils.toMap(field, value)
							);
				}
				
				return CommonUtils.toMap("range", 
							CommonUtils.toMap(field,
								CommonUtils.toMap(conditionOperator.name().toLowerCase(), value)
							)
						);
			}
			
			Map<String, Object> query = CommonUtils.toMap(field, CommonUtils.toMap("query", value));
			
			if(conditionOperator == ConditionOperator.AND)
			{
				query.put("operator", "and");
			}
			
			if(minMatch != null)
			{
				query.put("minimum_should_match", minMatch);
			}
			
			if(boost > 0)
			{
				query.put("boost", boost);
			}
			
			return CommonUtils.toMap("match", query);
		}
	}
	
	/**
	 * Conditions grouped using join operator.
	 */
	private Map<JoinOperator, List<Object>> conditionGroups = new HashMap<>();
	
	/**
	 * For sub queries, bean property to be used on main query object.
	 */
	private BeanProperty beanProperty;
	
	/**
	 * this query corresponding index type to be used.
	 */
	private Class<?> indexType;
	
	/**
	 * Flag indicating if score needs to be ignore. In other terms, whether constant score query
	 * needs to be built.
	 */
	private boolean ignoreScore;

	public TypeQueryDetails(Class<?> queryType)
	{
		loadClassLevelConditons(queryType);
		
		loadPropertyConditions(queryType);
	}
	
	private TypeQueryDetails(Class<?> subQueryType, BeanProperty beanProperty)
	{
		this.beanProperty = beanProperty;
		this.loadPropertyConditions(subQueryType);
	}
	
	/**
	 * Gets the this query corresponding index type to be used.
	 *
	 * @return the this query corresponding index type to be used
	 */
	public Class<?> getIndexType()
	{
		return indexType;
	}
	
	/**
	 * Loads conditions from search query annotation defined at class level.
	 * @param queryType
	 */
	private void loadClassLevelConditons(Class<?> queryType)
	{
		SearchQuery searchQuery = queryType.getAnnotation(SearchQuery.class);
		
		if(searchQuery == null)
		{
			throw new InvalidArgumentException("Specified type is not marked as search query - {}", queryType.getName());
		}
		
		this.indexType = searchQuery.indexType();
		this.ignoreScore = searchQuery.ignoreScore();
		
		//add null check conditions
		for(NullCheck check : searchQuery.nullFields())
		{
			addCondition(check.joinOperator(), new Condition(check.field(), true, false, check.boost()));
		}
		
		//add not null check conditions
		for(NullCheck check : searchQuery.notNullFields())
		{
			addCondition(check.joinOperator(), new Condition(check.field(), false, true, check.boost()));
		}
	}
	
	/**
	 * Adds specified condition to appropriate group of conditions.
	 * @param joinOperator
	 * @param condition
	 */
	private void addCondition(JoinOperator joinOperator, Object condition)
	{
		List<Object> conditions = this.conditionGroups.get(joinOperator);
		
		if(conditions == null)
		{
			conditions = new ArrayList<>();
			this.conditionGroups.put(joinOperator, conditions);
		}
		
		conditions.add(condition);
	}
	
	/**
	 * Loads property conditions from specified query type.
	 * @param queryType
	 */
	private void loadPropertyConditions(Class<?> queryType)
	{
		List<BeanProperty> properties = BeanProperty.loadProperties(queryType, true, false);
		
		if(properties == null)
		{
			throw new InvalidStateException("No properties found in specified query type - {}", queryType.getName());
		}
		
		SearchCondition searchCondition = null;
		Subquery subquery = null;
		String field = null;
		
		for(BeanProperty property : properties)
		{
			subquery = property.getAnnotation(Subquery.class);
			
			if(subquery != null)
			{
				addCondition( subquery.joinOperator(), new TypeQueryDetails(property.getType(), property) );
			}
			
			searchCondition = property.getAnnotation(SearchCondition.class);
			
			//ignore property which dont have search condition
			if(searchCondition == null)
			{
				continue;
			}
			
			field = searchCondition.field();
			field = StringUtils.isBlank(field) ? property.getName() : field;
			
			addCondition(searchCondition.joinOperator(), new Condition(field, property, searchCondition));
		}
	}
	
	private Map<String, Object> toBoolQuery(Object queryObj, TypeIndexDetails indexDetails)
	{
		Map<String, Object> conditionsGroupMap = new HashMap<>();
		List<Map<String, Object>> conditionMaps = null;
		
		Map<String, Object> conditionQuery = null;
		Object subqueryObj = null;
		
		//loop through groups
		for(JoinOperator joinOp : this.conditionGroups.keySet())
		{
			conditionMaps = new ArrayList<>();

			//loop though conditions in group
			for(Object condition : this.conditionGroups.get(joinOp))
			{
				if(condition instanceof Condition)
				{
					conditionQuery = ((Condition)condition).toQuery(queryObj, indexDetails);
				}
				else
				{
					subqueryObj = beanProperty.getValue(queryObj);
					
					if(subqueryObj == null)
					{
						continue;
					}
					
					conditionQuery = ((TypeQueryDetails)condition).toBoolQuery(subqueryObj, indexDetails);
				}
				
				if(conditionQuery != null)
				{
					conditionMaps.add(conditionQuery);
				}
			}
			
			//if conditions are found in this group
			if(!conditionMaps.isEmpty())
			{
				conditionsGroupMap.put(joinOp.name().toLowerCase(), conditionMaps);
			}
		}
		
		return CommonUtils.toMap("bool", conditionsGroupMap);
	}
	
	public Map<String, Object> buildQuery(Object queryObj, TypeIndexDetails indexDetails)
	{
		Map<String, Object> query = toBoolQuery(queryObj, indexDetails);
		
		if(ignoreScore)
		{
			query = CommonUtils.toMap("constant_score",
								CommonUtils.toMap("filter", query)
					);
		}
		
		return CommonUtils.toMap("query", query);
	}
}