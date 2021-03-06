/*
 * The MIT License (MIT)
 * Copyright (c) 2015 "Yukthi Techsoft Pvt. Ltd." (http://yukthi-tech.co.in)

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.yukthi.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.yukthi.utils.exceptions.InvalidArgumentException;
import com.yukthi.utils.exceptions.InvalidStateException;

/**
 * Reflection related utils
 * @author akiran
 */
public class ReflectionUtils
{
	/**
	 * Fetches annotation from method argument at index "argIdx" of annotation type specified by "annotationType"
	 * @param method Method from which argument annotation needs to be fetched
	 * @param argIdx Argument index from which annotation needs to be fetched
	 * @param annotationType Type of annotation 
	 * @return Annotation of type "A" defined in method parameter at index "argIdx". If not present, null is returned
	 */
	@SuppressWarnings("unchecked")
	public static <A extends Annotation> A getParameterAnnotation(Method method, int argIdx, Class<A> annotationType)
	{
		//get all parameter annotations
		Annotation paramAnnotations[][] = method.getParameterAnnotations();
		
		//if no parameter annotations are present
		if(paramAnnotations == null || paramAnnotations.length == 0)
		{
			return null;
		}

		//loop through parameter annotaions
		for(int i = 0; i < paramAnnotations[argIdx].length; i++)
		{
			//if match is found
			if(paramAnnotations[argIdx][i].annotationType().equals(annotationType))
			{
				return (A)paramAnnotations[argIdx][i];
			}
		}
		
		return null;
	}

	/**
	 * Used to set value of specified field irrespective of the field modifier
	 * @param bean Bean from which field value needs to be set
	 * @param field field from which value needs to be set
	 * @param value Value to be set
	 */
	public static void setFieldValue(Object bean, String field, Object value)
	{
		try
		{
			Field fieldObj = bean.getClass().getDeclaredField(field);
			fieldObj.setAccessible(true);
			fieldObj.set(bean, value);
		} catch(Exception ex)
		{
			throw new IllegalStateException("An error occurred while seting field value - " + field, ex);
		}
	}
	
	/**
	 * Used to fetch the field value of specified bean.
	 * @param bean Bean from whose field value needs to be fetched.
	 * @param field Field whose value needs to be fetched.
	 * @return Specified field value.
	 */
	public static Object getFieldValue(Object bean, String field)
	{
		try
		{
			Field fieldObj = bean.getClass().getDeclaredField(field);
			fieldObj.setAccessible(true);
			return fieldObj.get(bean);
		} catch(Exception ex)
		{
			throw new IllegalStateException("An error occurred while geting field value - " + field, ex);
		}
	}
	
	/**
	 * Fetches type of the nested field type
	 * @param cls Class in which nested field type needs to be fetched
	 * @param fieldName Nested field name whose type needs to be fetched
	 * @return Nested field type
	 */
	public static Class<?> getNestedFieldType(Class<?> cls, String fieldName)
	{
		String nestedPropPath[] = fieldName.split("\\.");
		int maxIdx = nestedPropPath.length - 1;
		Field field = null;
		Class<?> prevCls = cls;
		
		//loop through property path
		for(int i = 0; i <= maxIdx; i++)
		{
			try
			{
				//get intermediate property descriptor
				try
				{
					field = prevCls.getDeclaredField(nestedPropPath[i]);
				}catch(Exception ex)
				{
					field = null;
				}
				
				//if the property is not found or found as read only, return false
				if(field == null)
				{
					return null;
				}
				
				//if end of path is reached, set the final value and break the loop
				if(i == maxIdx)
				{
					return field.getType();
				}

				prevCls = field.getType();
			}catch(Exception ex)
			{
				throw new InvalidStateException(ex, "An error occurred while fetching nested field type - {}", fieldName);
			}
		}

		return null;
	}

	public static Object getNestedFieldValue(Object bean, String fieldName)
	{
		if(bean == null)
		{
			return null;
		}
		
		String nestedPropPath[] = fieldName.split("\\.");
		int maxIdx = nestedPropPath.length - 1;
		Field field = null;
		Object prevObject = bean;
		
		//loop through property path
		for(int i = 0; i <= maxIdx; i++)
		{
			try
			{
				//get intermediate property descriptor
				try
				{
					field = prevObject.getClass().getDeclaredField(nestedPropPath[i]);
				}catch(Exception ex)
				{
					field = null;
				}
				
				//if the property is not found or found as read only, return false
				if(field == null)
				{
					return null;
				}
				
				field.setAccessible(true);
				
				//if end of path is reached, set the final value and break the loop
				if(i == maxIdx)
				{
					return field.get(prevObject);
				}

				prevObject = field.get(prevObject);
			}catch(Exception ex)
			{
				throw new InvalidStateException(ex, "An error occurred while fetching nested field value - {} on type - {}", fieldName, bean.getClass().getName());
			}
		}

		return null;
	}

	public static void setNestedFieldValue(Object bean, String fieldName, Object value)
	{
		if(bean == null)
		{
			return;
		}
		
		String nestedPropPath[] = fieldName.split("\\.");
		int maxIdx = nestedPropPath.length - 1;
		Field field = null;
		Object prevObject = bean, newObject = null;
		
		//loop through property path
		for(int i = 0; i <= maxIdx; i++)
		{
			try
			{
				//get intermediate property descriptor
				try
				{
					field = prevObject.getClass().getDeclaredField(nestedPropPath[i]);
				}catch(Exception ex)
				{
					field = null;
				}
				
				//if the property is not found or found as read only, return false
				if(field == null)
				{
					throw new InvalidArgumentException("Invalid nested field '{}' specified for bean type - {}", fieldName, bean.getClass().getName());
				}
				
				field.setAccessible(true);
				
				//if end of path is reached, set the final value and break the loop
				if(i == maxIdx)
				{
					field.set(prevObject, value);
					return;
				}

				newObject = field.get(prevObject);
				
				//create intermediate beans as needed
				if(newObject == null)
				{
					try
					{
						newObject = field.getType().newInstance();
						field.set(prevObject, newObject);
					}catch(Exception ex)
					{
						throw new InvalidStateException("Failed to created instance of type - {}", field.getType().getName());
					}
				}
				
				prevObject = newObject;
			}catch(Exception ex)
			{
				throw new InvalidStateException(ex, "An error occurred while fetching nested field value - {} on type - {}", fieldName, bean.getClass().getName());
			}
		}
	}
}
