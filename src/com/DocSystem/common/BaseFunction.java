package com.DocSystem.common;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.wltea.analyzer.lucene.IKAnalyzer;

import util.ReadProperties;
import util.ReturnAjax;
import util.LuceneUtil.LuceneUtil2;

import com.DocSystem.common.CommonAction.CommonAction;
import com.DocSystem.common.constants.LICENSE_RESULT;
import com.DocSystem.commonService.ProxyThread;
import com.DocSystem.commonService.ShareThread;
import com.DocSystem.controller.URLInfo;
import com.DocSystem.entity.Doc;
import com.DocSystem.entity.DocLock;
import com.DocSystem.entity.Repos;
import com.DocSystem.entity.User;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public class BaseFunction{	
	protected static final long CONST_SECOND = 1000;
	protected static final long CONST_MINUTE = 60*1000;
	protected static final long CONST_HOUR = 60*60*1000;
	protected static final long CONST_DAY = 24*CONST_HOUR;
	protected static final long CONST_MONTH = 30*CONST_DAY;
	protected static final long CONST_YEAR = 12*CONST_MONTH;

	//应用路径
    protected static String docSysIniPath = null;
    protected static String docSysWebPath = null;
    protected static String webappsPath = null;
	
    //系统License
    public static License systemLicenseInfo = null;
	protected static long licenseCheckTimer = 0;
	
    //OnlyOffice License
    public static OfficeLicense officeLicenseInfo = null;

	public static ConcurrentHashMap<Integer, ConcurrentHashMap<String, DocLock>> docLocksMap = new ConcurrentHashMap<Integer, ConcurrentHashMap<String, DocLock>>();
	protected static ConcurrentHashMap<Integer, DocLock> reposLocksMap = new ConcurrentHashMap<Integer, DocLock>();
	
	public static int OSType = OS.UNKOWN; //
	
	//DocSysType
    protected static int docSysType = 0; //0: Community Edition 1: Professional Edition 2: Enterprise Edition
    
    protected static int isSalesServer = 0;
	protected static String serverHost = null;
	
    static {
    	initOSType();
    	docSysWebPath = Path.getWebPath(OSType);
    	webappsPath = Path.getDocSysWebParentPath(docSysWebPath);
		docSysIniPath = webappsPath + "docSys.ini/";   
    	initSystemLicenseInfo();
    	initOfficeLicenseInfo();
		serverHost = getServerHost();		
    }
    
	private static void initSystemLicenseInfo() {
		System.out.println("initSystemLicenseInfo() ");
		//Default systemLicenseInfo
		systemLicenseInfo = new License();
		systemLicenseInfo.type = constants.DocSys_Community_Edition;
		systemLicenseInfo.usersCount = null;	//无限制
		systemLicenseInfo.expireTime = null; //长期有效
		systemLicenseInfo.hasLicense = false;
	}
	
	protected boolean systemLicenseInfoCheck(ReturnAjax rt) {
		if(systemLicenseInfo.expireTime != null)
		{	
			long curTime = new Date().getTime();
			if(systemLicenseInfo.expireTime < curTime) 
			{
				rt.setError("证书已过期，请购买商业版证书！");
				return false;	
			}
		}
		if(systemLicenseInfo.state != null)
		{	
			if(systemLicenseInfo.state == 0) 
			{
				rt.setError("证书已失效，请重新购买商业版证书！");
				return false;	
			}
		}
		
		return true;
	}

    public static String getServerHost() {
    	String value = ReadProperties.getValue(docSysIniPath + "mxsdoc.conf", "serverHost");
        if(value != null && !value.isEmpty())
        {
        	return value;
        }

        return "http://dw.gofreeteam.com";
    }
    
	private static void initOfficeLicenseInfo() {
		//Default licenseInfo
		officeLicenseInfo = new OfficeLicense();
		officeLicenseInfo.count =  1;
		officeLicenseInfo.type = LICENSE_RESULT.Success;
		officeLicenseInfo.packageType = constants.PACKAGE_TYPE_OS;
		officeLicenseInfo.mode = constants.LICENSE_MODE.None;
		officeLicenseInfo.branding = false;
		officeLicenseInfo.connections = constants.LICENSE_CONNECTIONS;
		officeLicenseInfo.customization = false;
		officeLicenseInfo.light = false;
		officeLicenseInfo.usersCount = 20;
		officeLicenseInfo.usersExpire = constants.LICENSE_EXPIRE_USERS_ONE_DAY;
		officeLicenseInfo.hasLicense = false;
		officeLicenseInfo.plugins= false;
		//licenseInfo.put("buildDate", oBuildDate);		
		//licenseInfo.put("endDate", null);	
	}
	
	private static int initOSType() {
		String OSName = System.getProperty("os.name"); 
		System.out.println("OSName:"+ OSName);
		String os = OSName.toLowerCase();
		OSType = OS.UNKOWN;
		if(os.startsWith("win"))
		{
			OSType = OS.Windows;
		}
		else if(os.startsWith("linux"))
		{
			OSType = OS.Linux;
		}
		else if(os.startsWith("mac"))
		{
			OSType = OS.MacOS;
		}
		return OSType;
	}
	
	protected static boolean isWinOS() {
		return OS.isWinOS(OSType);
	}	

	//分享代理服务线程（一个服务器只允许启动一个）
	protected static ProxyThread proxyThread = null;
	//远程分享服务线程（一个服务器只允许启动一个）
	protected static ShareThread shareThread = null;
	
	
	protected static ConcurrentHashMap<Integer, UniqueAction> uniqueActionHashMap = new ConcurrentHashMap<Integer, UniqueAction>();
	protected boolean insertUniqueCommonAction(CommonAction action)
	{
		Doc srcDoc = action.getDoc();
		if(srcDoc == null)
		{
			return false;
		}
		
		Integer reposId = srcDoc.getVid();
		UniqueAction uniqueAction = uniqueActionHashMap.get(reposId);
		if(uniqueAction == null)
		{
			//System.out.println("insertUniqueCommonAction create uniqueAction for repos:" + reposId);
			UniqueAction newUniqueAction = new UniqueAction();
			uniqueActionHashMap.put(reposId, newUniqueAction);
			uniqueAction = newUniqueAction;
		}
		
		ConcurrentHashMap<Long, CommonAction> uniqueCommonActionHashMap = uniqueAction.getUniqueCommonActionHashMap();
		List<CommonAction> uniqueCommonActionList = uniqueAction.getUniqueCommonActionList();		

		//System.out.println("insertUniqueCommonAction actionType:" + action.getAction() + " docType:" + action.getDocType() + " actionId:" + action.getType() + " doc:"+ srcDoc.getDocId() + " " + srcDoc.getPath() + srcDoc.getName());
		CommonAction tempAction = uniqueCommonActionHashMap.get(srcDoc.getDocId());
		if(tempAction != null && tempAction.getType() == action.getType() && tempAction.getAction() == action.getAction() && tempAction.getDocType() == action.getDocType())
		{
			//System.out.println("insertUniqueCommonAction action for doc:"+ srcDoc.getDocId() + " [" + srcDoc.getPath() + srcDoc.getName() + "] alreay in uniqueActionList");
			return false;
		}
		
		uniqueCommonActionHashMap.put(srcDoc.getDocId(), action);
		uniqueCommonActionList.add(action);
		System.out.println("insertUniqueCommonAction actionType:" + action.getAction() + " docType:" + action.getDocType() + " actionId:" + action.getType() + " doc:"+ srcDoc.getDocId() + " " + srcDoc.getPath() + srcDoc.getName());
		return true;
	}
	
	public static Doc buildBasicDoc(Integer reposId, Long docId, Long pid, String reposPath, String path, String name, Integer level, Integer type, boolean isRealDoc, String localRootPath, String localVRootPath, Long size, String checkSum) 
	{
		Doc doc = DocUtil.buildBasicDoc(reposId, docId, pid, reposPath, path, name, level, type, isRealDoc, localRootPath, localVRootPath, size, checkSum);
		doc.isBussiness = systemLicenseInfo.hasLicense;
		return doc;
	}
	
	protected Doc buildRootDoc(Repos repos, String localRootPath, String localVRootPath) 
	{
		//String localRootPath = getReposRealPath(repos);
		//String localVRootPath = getReposVirtualPath(repos);
		return buildBasicDoc(repos.getId(), 0L, -1L, repos.getPath() + repos.getId() + "/", "", "", 0, 2, true, localRootPath, localVRootPath, null, null);
	}
	
	//VirtualDoc 的vid docId pid level都是和RealDoc一样的
	protected Doc buildVDoc(Doc doc) 
	{
		if(doc.getIsRealDoc())
		{
			Doc vDoc = buildBasicDoc(doc.getVid(), doc.getDocId(), doc.getPid(), "", "", Path.getVDocName(doc), 0, 2, false, doc.getLocalVRootPath(), doc.getLocalVRootPath(), null, null); 
			vDoc.setContent(doc.getContent());
			return vDoc;
		}
		
		System.out.println("buildVDoc() doc already is VDoc");
		return doc;
	}
		
	/***************************** json相关接口 ***************************/
	/**
	 * 向页面返回json信息
	 * @param obj
	 * @param response
	 */
	protected void writeJson(Object obj,HttpServletResponse response) {
		
		try {
			response.setCharacterEncoding("UTF-8");
			String json = JSON.toJSONStringWithDateFormat(obj, "yyy-MM-dd HH:mm:ss");
			PrintWriter pw = response.getWriter();
			response.setContentType("application/javascript");
			pw.write(json);
			pw.flush();
			pw.close();
		} catch (IOException e) {
			System.out.println("BaseController>writeJson  ERROR!");
			e.printStackTrace();
		}
		
	}
	
	/**
	 * 向页面返回Text信息
	 * @param obj
	 * @param response
	 */
	
	protected void writeText(String text,HttpServletResponse response) {
		
		try {
			response.setCharacterEncoding("UTF-8");
			PrintWriter pw = response.getWriter();
			response.setContentType("application/javascript");
			pw.write(text);
			pw.flush();
			pw.close();
		} catch (IOException e) {
			System.out.println("BaseController>writeJson  ERROR!");
			e.printStackTrace();
		}
		
	}
	
	/**
	 * 向页面返回Html信息
	 * @param obj
	 * @param response
	 */

	protected void writeHtml(String html,HttpServletResponse response) {

		try {
			response.setCharacterEncoding("UTF-8");
			PrintWriter pw = response.getWriter();
			response.setContentType("text/html;charset=UTF-8");
			pw.write(html);
			pw.flush();
			pw.close();
		} catch (IOException e) {
			System.out.println("BaseController>writeJson  ERROR!");
			e.printStackTrace();
		}

	}
	
	/**
	 * 设置cookie
	 * @param response
	 * @param name  cookie名字
	 * @param value cookie值
	 * @param maxAge cookie生命周期  以秒为单位
	 */
	public static void addCookie(HttpServletResponse response,String name,String value,int maxAge){
	    Cookie cookie = new Cookie(name,value);
	    cookie.setPath("/");
	    if(maxAge>0)  cookie.setMaxAge(maxAge);
	    response.addCookie(cookie);
	}
	
	/**
	 * 根据名字获取cookie
	 * @param request
	 * @param name cookie名字
	 * @return
	 */
	public static Cookie getCookieByName(HttpServletRequest request,String name){
	    Map<String,Cookie> cookieMap = ReadCookieMap(request);
	    if(cookieMap.containsKey(name)){
	        Cookie cookie = (Cookie)cookieMap.get(name);
	        return cookie;
	    }else{
	        return null;
	    }   
	}
	
	/**
	 * 将cookie封装到Map里面
	 * @param request
	 * @return
	 */
	private static Map<String,Cookie> ReadCookieMap(HttpServletRequest request){  
	    Map<String,Cookie> cookieMap = new HashMap<String,Cookie>();
	    Cookie[] cookies = request.getCookies();
	    if(null!=cookies){
	        for(Cookie cookie : cookies){
	            cookieMap.put(cookie.getName(), cookie);
	        }
	    }
	    return cookieMap;
	}
	
	/***************************文件排序接口*****************************/
	protected List<Doc> sortDocList(List<Doc> docList, String sort) 
	{
		Collections.sort(docList,
				new Comparator<Doc>() {
					HashMap<Integer,String> sortMap = buildSortMap(sort);
			
					public int compare(Doc u1, Doc u2) {
						long diff = getSortDiff(u1, u2, sortMap);	//
						if (diff > 0) 
						{
							return 1;
						}
						else if (diff < 0) 
						{
							return -1;
						}
						return 0;
					}

					private long getSortDiff(Doc u1, Doc u2, HashMap<Integer, String> sortMap) {
						long diff = 0;
						for(int i=0; i< sortMap.size(); i++)
						{
							String sortType = sortMap.get(i);
							System.out.println("sortType:" + sortType);
							if(sortType != null)
							{
								diff = getSortDiffBySortType(u1, u2, sortType);
								if(diff != 0)
								{
									return diff;
								}
							}
						}
						return diff;
					}

					private long getSortDiffBySortType(Doc u1, Doc u2, String sortType) {
						switch(sortType)
						{
						case "type":
							return u1.getType() - u2.getType();
						case "typeR":
							return u2.getType() - u1.getType();
						case "name":
							return u1.getName().compareTo(u2.getName());
						case "nameR":
							return u2.getName().compareTo(u1.getName());
						case "size":
							return u1.getSize() - u2.getSize();
						case "sizeR":
							return u2.getSize() - u1.getSize();
						case "modifyTime":
							return u1.getLatestEditTime() - u2.getLatestEditTime();
						case "modifyTimeR":
							return u2.getLatestEditTime() - u1.getLatestEditTime();
						case "createTime":
							return u1.getCreateTime() - u2.getCreateTime();
						case "createTimeR":
							return u2.getCreateTime() - u1.getCreateTime();
						}
						return 0;
					}
					
					private HashMap<Integer, String> buildSortMap(String sort) 
					{
						String [] sortStrs = sort.split(" ");
						HashMap<Integer, String> sortMap = new HashMap<Integer, String>();
						for(int i=0 ; i< sortStrs.length; i++)
						{
							String sortStr = sortStrs[i];
							System.out.println("sortStr:" + sortStr);
							if(sortStr != null && !sortStr.isEmpty())
							{
								sortMap.put(i, sortStr);
							}
						}
						
						return sortMap;
					}
				}
		);
		
		return docList;
	}
	
	/****************************** 文件操作相关接口 ***********************************/	

    //将dstDirPath同步成srcDirPath
	protected boolean syncUpFolder(String srcParentPath,String srcName, String dstParentPath, String dstName,boolean modifyEnable) 
	{
		String srcPath =  srcParentPath + srcName;
		String dstPath =  dstParentPath + dstName;
		
		//If the dstDirPath 不存在则，直接复制整个目录
		File dstFolder = new File(dstPath);
		if(!dstFolder.exists())
		{
			return FileUtil.copyDir(srcPath,dstPath,true);
		}
	
		if(dstFolder.isFile())
		{
			System.out.println(dstPath + " 不是目录！");
			return false;
		}

		try {
			
			//SyncUpForDelete
			syncUpForDelete(srcParentPath,srcName,dstParentPath,dstName);
			
			//SyncUpForAddAndModify
			if(modifyEnable)
			{
				syncUpForAddAndModify(srcParentPath,srcName,dstParentPath,dstName);
			}
			else
			{
				syncUpForAdd(srcParentPath,srcName,dstParentPath,dstName);
			}
		} catch (IOException e) {
			System.out.println("syncUpFolder() Exception!");
			e.printStackTrace();
			return false;
		}
		return true;
	}
    
    private void syncUpForAdd(String srcParentPath,String srcName, String dstParentPath, String dstName) throws IOException {
    	System.out.println("syncUpForAddAndModify() srcParentPath:" + srcParentPath + " srcName:" + srcName + " dstParentPath:" + dstParentPath + " dstName:" + dstName );
    	String srcPath = srcParentPath + srcName;
    	String dstPath = dstParentPath + dstName;
    	
    	File srcFile = new File(srcPath);
    	File dstFile = new File(dstPath);
        if(srcFile.exists())
        {
        	if(!dstFile.exists())
        	{
        		if(srcFile.isDirectory())
        		{
        			dstFile.mkdir();	//新建目录
        		}
        		else
        		{
        			FileUtil.copyFile(srcPath,dstPath,false);
        		}
        	}
        	
            //Go through the subEntries
        	if(srcFile.isDirectory())
        	{
    			File[] tmp=srcFile.listFiles();
        		for(int i=0;i<tmp.length;i++)
        		{
        			String subEntryName = tmp[i].getName();
        			syncUpForAdd(srcPath+"/", subEntryName, dstPath + "/", subEntryName);
                }
            }
       }	
	}

	private void syncUpForAddAndModify(String srcParentPath,String srcName, String dstParentPath, String dstName) throws IOException {
    	System.out.println("syncUpForAddAndModify() srcParentPath:" + srcParentPath + " srcName:" + srcName + " dstParentPath:" + dstParentPath + " dstName:" + dstName );
    	String srcPath = srcParentPath + srcName;
    	String dstPath = dstParentPath + dstName;
    	
    	File srcFile = new File(srcPath);
    	File dstFile = new File(dstPath);
        if(srcFile.exists())
        {
        	if(!dstFile.exists())
        	{
        		if(srcFile.isDirectory())
        		{
        			dstFile.mkdir();	//新建目录
        		}
        		else
        		{
        			FileUtil.copyFile(srcPath,dstPath,false);
        		}
        	}
        	else
        	{
        		if(srcFile.isFile())
        		{
        			FileUtil.copyFile(srcPath,dstPath,true);
        		}
        	}
        	
            //Go through the subEntries
        	if(srcFile.isDirectory())
        	{
    			File[] tmp=srcFile.listFiles();
        		for(int i=0;i<tmp.length;i++)
        		{
        			String subEntryName = tmp[i].getName();
        			syncUpForAddAndModify(srcPath+"/", subEntryName, dstPath + "/", subEntryName);
                }
            }
       }	
	}

	private void syncUpForDelete(String srcParentPath, String srcName, String dstParentPath, String dstName) {
    	System.out.println("syncUpForDelete() srcParentPath:" + srcParentPath + " srcName:" + srcName + " dstParentPath:" + dstParentPath + " dstName:" + dstName );
    	String srcPath = srcParentPath + srcName;
    	String dstPath = dstParentPath + dstName;
    	
    	File srcFile = new File(srcPath);
    	File dstFile = new File(dstPath);
        if(dstFile.exists())
        {
        	if(!srcFile.exists())
        	{
        		FileUtil.delDir(dstPath);	//删除目录或文件
        		return;
        	}
        	else
        	{
        		if(dstFile.isDirectory() != srcFile.isDirectory())	//类型不同
        		{
        			FileUtil.delDir(dstPath); //删除目录或文件
            		return;
        		}
        	}
        	
            //Go through the subEntries
        	if(dstFile.isDirectory())
        	{
    			File[] tmp=dstFile.listFiles();
        		for(int i=0;i<tmp.length;i++)
        		{
        			String subEntryName = tmp[i].getName();
        			syncUpForDelete(srcPath+"/", subEntryName, dstPath + "/", subEntryName);
                }
            }
       }
	}
	
	/****************** 线程锁接口 *********************************************/
	protected static final Object syncLock = new Object(); 
	
	/****************** 路径相关的接口 *****************************************/
	//WebTmpPath was accessable for web
	protected String getWebUserTmpPath(User login_user) {
        String webUserTmpPath =  docSysWebPath +  "tmp/" + login_user.getId() + "/";
        System.out.println("getWebUserTmpPath() webUserTmpPath:" + webUserTmpPath);
		return webUserTmpPath;
	}

	protected String getWebUserTmpPath(User login_user, boolean autoCreate) {
        String webUserTmpPath =  docSysWebPath +  "tmp/" + login_user.getId() + "/";
        System.out.println("getWebUserTmpPath() webUserTmpPath:" + webUserTmpPath);
		if(autoCreate == true)
		{
			File dir = new File(webUserTmpPath);
			if(!dir.exists())
			{
				dir.mkdirs();
			}
		}
        return webUserTmpPath;
	}
	
	//WebTmpPath was 
	protected String getWebUploadPath() {
		String webTmpPath =  docSysWebPath +  "uploads/";
	    System.out.println("getWebUploadPath() webTmpPath:" + webTmpPath);
		return webTmpPath;
	}
	
	//WebTmpPath was 
	protected String getWebTmpPath() {
        String webTmpPath =  docSysWebPath +  "tmp/";
        System.out.println("getWebTmpPath() webTmpPath:" + webTmpPath);
		return webTmpPath;
	}
	
	protected String getWebTmpPathForPreview() {
        String webTmpPath =  docSysWebPath +  "tmp/preview/";
        System.out.println("getWebTmpPathForPreview() webTmpPath:" + webTmpPath);
		return webTmpPath;
	}
	
	protected static String getLicensePath() {
		return docSysIniPath + "license/";
	}
	
	//获取JavaHome
    public String getJavaHome() {
    	String path = null;
        path = ReadProperties.read("docSysConfig.properties", "javaHome");
        if(path != null && !path.isEmpty())
        {
        	return Path.localDirPathFormat(path, OSType);
        }
        
        //tomcat目录
        path = Path.getParentPath(docSysWebPath, 2, OSType) + "Java/jdk/";
        return path;
    }
    
	
	//获取Tomcat的安装路径
    public String getTomcatPath() {
    	//get Tomcat Path From Config File
    	String path = null;
        path = ReadProperties.read("docSysConfig.properties", "tomcatPath");
        if(path != null && !path.isEmpty())
        {
        	return Path.localDirPathFormat(path, OSType);
        }

        //tomcat目录
        path = Path.getParentPath(docSysWebPath, 2, OSType);
        return path;
    }
    
	//获取OpenOffice的安装路径
    public String getOpenOfficePath() {
    	//get OpenOffice Home From Config File
    	String path = null;
    	path = ReadProperties.read("docSysConfig.properties", "openOfficePath");
        if(path != null && !path.isEmpty())
        {
        	return Path.localDirPathFormat(path, OSType);
        }

        switch(OSType)
        {
        case OS.Linux: 
        	path = "/opt/openoffice.org4/";
        	break;
        case OS.Windows:
        	path = "C:/Program Files (x86)/OpenOffice 4/";
        	break;
        case OS.MacOS:
        	path = "/Applications/OpenOffice.org.app/Contents/";
        	break;
        }
        return path;
    }
    
	//日志管理	
	protected static boolean addUserPreferServer(String serverName, String serverUrl, String userName, String pwd, User accessUser)
    {
		UserPreferServer server = new UserPreferServer();
		server.createTime = new Date().getTime();
		
		server.serverName = serverName;
		server.serverUrl = serverUrl;
		server.serverUserName = userName;
		server.serverUserPwd = pwd;
		
		server.userId = accessUser.getId();
		server.userName = accessUser.getName();
		
		server.id = server.userId + "_" + serverUrl.hashCode() + "_" + server.createTime;
		
		String indexLib = getIndexLibPathForUserPreferServer();
		return addUserPreferServerIndex(server, indexLib);
    }

	private static boolean addUserPreferServerIndex(UserPreferServer server, String indexLib) {
    	System.out.println("addUserPreferServerIndex() id:" + server.id + " indexLib:"+indexLib);    	
    	
    	Analyzer analyzer = null;
		Directory directory = null;
		IndexWriter indexWriter = null;
    	
		try {
	    	Date date1 = new Date();
	    	analyzer = new IKAnalyzer();
	    	directory = FSDirectory.open(new File(indexLib));

	        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_46, analyzer);
	        indexWriter = new IndexWriter(directory, config);
	
	        Document document = LuceneUtil2.buildDocumentForObject(server);
	        indexWriter.addDocument(document);	        
	        
	        indexWriter.commit();
	        
	        indexWriter.close();
	        indexWriter = null;
	        directory.close();
	        directory = null;
	        analyzer.close();
	        analyzer = null;
	        
			Date date2 = new Date();
	        System.out.println("addUserPreferServerIndex() 创建索引耗时：" + (date2.getTime() - date1.getTime()) + "ms\n");
	    	return true;
		} catch (Exception e) {
			closeResource(indexWriter, directory, analyzer);
	        System.out.println("addUserPreferServerIndex() 异常");
			e.printStackTrace();
			return false;
		}
    }

	//日志管理	
	protected static boolean addSystemLog(HttpServletRequest request, User user, String event, String subEvent, String action, String result, Repos repos, Doc doc, Doc newDoc, String content)
    {
		SystemLog log = new SystemLog();
		log.time = new Date().getTime();
		
		if(request == null)
		{
			log.ip = "未知";			
		}
		else
		{
			log.ip = getRequestIpAddress(request);
		}
		
		
		if(user == null)
		{
			log.userId = "";
			log.userName = "未知用户";							
		}
		else
		{
			log.userId = user.getId() + "";
			log.userName = user.getName();			
		}
		
		log.event = event;
		log.subEvent = subEvent;
		log.action = action;
		log.result = result;
		log.reposName = "";
		if(repos != null)
		{
			log.reposName = repos.getName();
		}
		log.path = "";
		log.name = "";
		if(doc != null)
		{
			log.path = doc.getPath();
			log.name = doc.getName();			
		}
		log.newPath = "";
		log.newName = "";
		if(newDoc != null)
		{
			log.newPath = newDoc.getPath();
			log.newName = newDoc.getName();
		}
		log.content = content;
		
		log.id = log.userName  + "-" + log.event + "-" + log.subEvent + "-" + log.time;
		
		String indexLib = getIndexLibPathForSystemLog();
		return addSystemLogIndex(log, indexLib);
    }
	
	protected static boolean addSystemLogIndex(SystemLog log, String indexLib)
    {	
    	System.out.println("addSystemLogIndex() id:" + log.id + " indexLib:"+indexLib);    	
    	
    	Analyzer analyzer = null;
		Directory directory = null;
		IndexWriter indexWriter = null;
    	
		try {
	    	Date date1 = new Date();
	    	analyzer = new IKAnalyzer();
	    	directory = FSDirectory.open(new File(indexLib));

	        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_46, analyzer);
	        indexWriter = new IndexWriter(directory, config);
	
	        Document document = LuceneUtil2.buildDocumentForObject(log);
	        indexWriter.addDocument(document);	        
	        
	        indexWriter.commit();
	        
	        indexWriter.close();
	        indexWriter = null;
	        directory.close();
	        directory = null;
	        analyzer.close();
	        analyzer = null;
	        
			Date date2 = new Date();
	        System.out.println("addSystemLogIndex() 创建索引耗时：" + (date2.getTime() - date1.getTime()) + "ms\n");
	    	return true;
		} catch (Exception e) {
			closeResource(indexWriter, directory, analyzer);
	        System.out.println("addSystemLogIndex() 异常");
			e.printStackTrace();
			return false;
		}
    }
	
	protected static void closeResource(IndexWriter indexWriter, Directory directory, Analyzer analyzer) {
		try {
        	if(indexWriter!=null)
        	{
        		indexWriter.close();
        	}
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		
		if(directory != null)
		{
			try {
				directory.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if(analyzer != null)
		{
			analyzer.close();
		}
	}
    
	protected static String getIndexLibPathForSystemLog() 
	{
		Date curTime = new Date();
		return getIndexLibPathForSystemLog(curTime);
	}
		
	protected static String getIndexLibPathForSystemLog(Date date) 
	{
		//按月创建Log
		String indexLibName = "SystemLog-" + date.getYear() + "-" + date.getMonth();
		String path = getSystemLogStorePath() + indexLibName + "/";
		return path;
	}
	
    
	protected static String getIndexLibPathForUserPreferServer() {
		return getDBStorePath() + "UserPreferServer/";
	}
	
	protected static String getDBStorePath() {
    	String path = null;
    	path = ReadProperties.read("docSysConfig.properties", "DBStorePath");
        if(path != null && !path.isEmpty())
        {
        	return Path.localDirPathFormat(path, OSType);
        }

        switch(OSType)
        {
        case OS.Windows:
        	path = "C:/DocSysDB/";
        	break;
        case OS.Linux: 
        	path = "/data/DocSysDB/";
        	break;
        case OS.MacOS:
        	path = "/data/DocSysDB/";
        	break;
        }
        return path;
    }	
	
	
	protected static String getSystemLogStorePath() {
    	String path = null;
    	path = ReadProperties.read("docSysConfig.properties", "SystemLogStorePath");
        if(path != null && !path.isEmpty())
        {
        	return Path.localDirPathFormat(path, OSType);
        }

        switch(OSType)
        {
        case OS.Windows:
        	path = "C:/DocSysLog/SystemLog/";
        	break;
        case OS.Linux: 
        	path = "/data/DocSysLog/SystemLog/";
        	break;
        case OS.MacOS:
        	path = "/data/DocSysLog/SystemLog/";
        	break;
        }
        return path;
    }	
	
	protected static String getRequestIpAddress(HttpServletRequest request) {
	    String ip = null;

	    //X-Forwarded-For：Squid 服务代理
	    String ipAddresses = request.getHeader("X-Forwarded-For");
	    if (ipAddresses == null || ipAddresses.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) 
	    {
	        //Proxy-Client-IP：apache 服务代理
	        ipAddresses = request.getHeader("Proxy-Client-IP");
	    }
	    
	    if (ipAddresses == null || ipAddresses.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) 
	    {
	        //WL-Proxy-Client-IP：weblogic 服务代理
	        ipAddresses = request.getHeader("WL-Proxy-Client-IP");
	    }
	    
	    if (ipAddresses == null || ipAddresses.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) 
	    {
	        //HTTP_CLIENT_IP：有些代理服务器
	        ipAddresses = request.getHeader("HTTP_CLIENT_IP");
	    }
	    
	    if (ipAddresses == null || ipAddresses.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) 
	    {
	        //X-Real-IP：nginx服务代理
	        ipAddresses = request.getHeader("X-Real-IP");
	    }

	    //有些网络通过多层代理，那么获取到的ip就会有多个，一般都是通过逗号（,）分割开来，并且第一个ip为客户端的真实IP
	    if (ipAddresses != null && ipAddresses.length() != 0) {
	        ip = ipAddresses.split(",")[0];
	    }

	    //还是不能获取到，最后再通过request.getRemoteAddr();获取
	    if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) 
	    {
	        ip = request.getRemoteAddr();
	    }
	    
	    return ip.equals("0:0:0:0:0:0:0:1")?"127.0.0.1":ip;
	}
	
	protected URLInfo getUrlInfoFromUrl(String url) {
		Log.info("getUrlInfoFromUrl()", "url:" + url);
		
		URLInfo urlInfo = new URLInfo();
		
	    String subStrs1[] = url.split("://");
	    if(subStrs1.length < 2)
	    {
	    	Log.info("getUrlInfoFromUrl()", "非法URL");
	    	return null;
	    }
	    
	    //set prefix
	    urlInfo.prefix = subStrs1[0] + "://";
	    String hostWithPortAndParams = subStrs1[1];	    
	    String subStrs2[] = hostWithPortAndParams.split("/");
	    String hostWithPort = subStrs2[0];
	    
	    String subStrs3[] = hostWithPort.split(":");
	    if(subStrs3.length < 2)
	    {
	    	urlInfo.host = subStrs3[0];
	    }
	    else
	    {
	    	urlInfo.host = subStrs3[0];
	    	urlInfo.port = subStrs3[1];  	
	    }
	    Log.printObject("getUrlInfoFromUrl() urlInfo:", urlInfo);
		return urlInfo;
	}
	
}
