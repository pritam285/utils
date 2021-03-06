package com.yukthi.dao.qry.impl;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.yukthi.dao.qry.ConnectionSource;
import com.yukthi.dao.qry.QueryManager;
import com.yukthi.dao.qry.TransactionManager;

public class XMLQueryFactory
{
	private static Logger logger = LogManager.getLogger(XMLQueryFactory.class);

	private static Map<String, QueryManager> resToManager = new HashMap<String, QueryManager>();

	public static QueryManager loadFromXML(InputStream is)
	{
		return loadFromXML(is, null);
	}

	public static QueryManager loadFromXML(InputStream is, ConnectionSource connectionSource)
	{
		XMLQuerySource xmlSource = new XMLQuerySource(is, connectionSource);

		TransactionManager transactionManager = TransactionManager.getTransactionManager(xmlSource.getTransactionManagerName());
		
		QueryManager manager = new QueryManager(xmlSource, transactionManager);
		logger.debug("Loaded Query manager successfully.");

		return manager;
	}

	public static QueryManager loadFromXML(String xmlRes, ConnectionSource connectionSource, boolean reload)
	{
		QueryManager manager = reload? null: resToManager.get(xmlRes);

		if(manager != null)
			return manager;

		logger.debug("Started loading resource: " + xmlRes);
		InputStream is = XMLQueryFactory.class.getResourceAsStream(xmlRes);
		
		manager = loadFromXML(is, connectionSource);

		resToManager.put(xmlRes, manager);
		logger.debug("Loaded XML resource successfully: " + xmlRes);

		return manager;
	}

	public static QueryManager loadFromXML(String xmlRes, boolean reload)
	{
		return loadFromXML(xmlRes, null, reload);
	}

	public static QueryManager loadFromXML(String xmlRes)
	{
		return loadFromXML(xmlRes, false);
	}

	public static XMLQuerySource loadQuerySourceFromXML(String xmlRes)
	{
		QueryManager queryManager = loadFromXML(xmlRes, false);
		return (XMLQuerySource)queryManager.getQuerySource();
	}
}
