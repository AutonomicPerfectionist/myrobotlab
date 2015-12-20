/**
 *                    
 * @author greg (at) myrobotlab.org
 *  
 * This file is part of MyRobotLab (http://myrobotlab.org).
 *
 * MyRobotLab is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version (subject to the "Classpath" exception
 * as provided in the LICENSE.txt file that accompanied this code).
 *
 * MyRobotLab is distributed in the hope that it will be useful or fun,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * All libraries in thirdParty bundle are subject to their own license
 * requirements - please refer to http://myrobotlab.org/libraries for 
 * details.
 * 
 * Enjoy !
 * 
 * */

package org.myrobotlab.framework;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SimpleTimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

import org.myrobotlab.cache.LRUMethodCache;
import org.myrobotlab.codec.CodecUtils;
import org.myrobotlab.codec.Recorder;
import org.myrobotlab.fileLib.FileIO;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.Logging;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.net.CommunicationManager;
import org.myrobotlab.net.Heartbeat;
import org.myrobotlab.service.Runtime;
import org.myrobotlab.service.interfaces.AuthorizationProvider;
import org.myrobotlab.service.interfaces.CommunicationInterface;
import org.myrobotlab.service.interfaces.Invoker;
import org.myrobotlab.service.interfaces.NameProvider;
import org.myrobotlab.service.interfaces.QueueReporter;
import org.myrobotlab.service.interfaces.ServiceInterface;
import org.slf4j.Logger;

/**
 * 
 * Service is the base of the MyRobotLab Service Oriented Architecture. All
 * meaningful Services derive from the Service class. There is a
 * _TemplateService.java in the org.myrobotlab.service package. This can be used
 * as a very fast template for creating new Services. Each Service begins with
 * two threads One is for the "OutBox" this delivers messages out of the
 * Service. The other is the "InBox" thread which processes all incoming
 * messages.
 * 
 */
public abstract class Service extends MessageService implements Runnable, Serializable, ServiceInterface, Invoker, QueueReporter {

	// FIXME upgrade to ScheduledExecutorService
	protected class Task extends TimerTask {

		Message msg;
		int interval = 0;

		public Task(int interval, String name, String method, Object... data) {
			this.msg = createMessage(name, method, data);
			this.interval = interval;
		}

		public Task(Task s) {
			this.msg = s.msg;
			this.interval = s.interval;
		}

		@Override
		public void run() {

			getInbox().add(msg);

			if (interval > 0) {
				Task t = new Task(this);
				// clear history list - becomes "new" message
				t.msg.historyList.clear();
				timer.schedule(t, interval);
			}
		}

	}

	/**
	 * a radix-tree of data -"DNA" Description of Neighboring Automata ;)
	 */
	transient static public final Index<ServiceReservation> dna = new Index<ServiceReservation>();

	private static final long serialVersionUID = 1L;

	transient public final static Logger log = LoggerFactory.getLogger(Service.class);

	/**
	 * key into Runtime's hosts of ServiceEnvironments mrlscheme://[gateway
	 * name]/scheme://key for gateway mrl://gateway/xmpp://incubator incubator
	 * if host == null the service is local
	 */
	private URI instanceId = null;

	private String name;

	private String simpleName; // used in gson encoding for getSimpleName()

	private String serviceClass;

	private String lastRecordingFilename;

	private boolean isRunning = false;

	transient protected Thread thisThread = null;


	transient protected Inbox inbox = null;

	transient Timer timer = null;
	
	/**
	 * a more capable task handler
	 */
	transient HashMap<String, Timer> tasks = new HashMap<String, Timer>();

	protected boolean allowDisplay = true;

	public final static String cfgDir = FileIO.getCfgDir();

	// transient private Serializer serializer = new Persister();

	transient protected Set<String> methodSet;

	transient protected SimpleDateFormat TSFormatter = new SimpleDateFormat("yyyyMMddHHmmssSSS");

	transient protected Calendar cal = Calendar.getInstance(new SimpleTimeZone(0, "GMT"));

	// recordings
	// static private boolean isRecording = false;
	static private Recorder recorder = null;

	transient public final String MESSAGE_RECORDING_FORMAT_XML = "MESSAGE_RECORDING_FORMAT_XML";

	transient public final String MESSAGE_RECORDING_FORMAT_BINARY = "MESSAGE_RECORDING_FORMAT_BINARY";


	// FIXME SecurityProvider
	protected static AuthorizationProvider security = null;

	private Status lastError = null;

	// FIXME - remove out of Peers, have Peers use this logic & pass in its
	// index
	static public void buildDNA(Index<ServiceReservation> myDNA, String myKey, String serviceClass, String comment) {
		String fullClassName;
		if (!serviceClass.contains(".")) {
			fullClassName = String.format("org.myrobotlab.service.%s", serviceClass);
		} else {
			fullClassName = serviceClass;
		}
		try {
			// get the class
			Class<?> theClass = Class.forName(fullClassName);

			Method method = theClass.getMethod("getPeers", String.class);
			Peers peers = (Peers) method.invoke(null, new Object[] { myKey });
			Index<ServiceReservation> peerDNA = peers.getDNA();
			ArrayList<ServiceReservation> flattenedPeerDNA = peerDNA.flatten();

			log.debug(String.format("processing %s.getPeers(%s) will process %d peers", serviceClass, myKey, flattenedPeerDNA.size()));

			// Two loops are necessary - because recursion should not start
			// until the entire level
			// of peers has been entered into the tree - this will build the
			// index level by level
			// versus depth first - necessary because the "upper" levels need to
			// process first
			// to influence the lower levels

			for (int x = 0; x < flattenedPeerDNA.size(); ++x) {
				ServiceReservation peersr = flattenedPeerDNA.get(x);

				// FIXME A BIT LAME - THE Index.crawlForData should be returning
				// Set<Map.Entry<?>>
				String peerKey = peersr.key;

				String fullKey = String.format("%s.%s", myKey, peerKey);
				ServiceReservation reservation = myDNA.get(fullKey);

				log.debug(String.format("%d (%s) - [%s]", x, fullKey, peersr.actualName));

				if (reservation == null) {
					log.debug(String.format("dna adding new key %s %s %s %s", fullKey, peersr.actualName, peersr.fullTypeName, comment));
					myDNA.put(fullKey, peersr);
				} else {
					log.debug(String.format("dna collision - replacing null values !!! %s", fullKey));
					StringBuffer sb = new StringBuffer();
					if (reservation.actualName == null) {
						sb.append(String.format(" updating actualName to %s ", peersr.actualName));
						reservation.actualName = peersr.actualName;
					}

					if (reservation.fullTypeName == null) {
						// FIXME check for dot ?
						sb.append(String.format(" updating peerType to %s ", peersr.fullTypeName));
						reservation.fullTypeName = peersr.fullTypeName;
					}

					if (reservation.comment == null) {
						sb.append(String.format(" updating comment to %s ", comment));
						reservation.comment = peersr.comment;
					}

					log.info(sb.toString());
				}
			}

			// recursion loop
			for (int x = 0; x < flattenedPeerDNA.size(); ++x) {
				ServiceReservation peersr = flattenedPeerDNA.get(x);
				buildDNA(myDNA, Peers.getPeerKey(myKey, peersr.key), peersr.fullTypeName, peersr.comment);
			}
		} catch (Exception e) {
			log.debug(String.format("%s does not have a getPeers", fullClassName));
		}
	}

	static public Index<ServiceReservation> buildDNA(String serviceClass) {
		return buildDNA("", serviceClass);
	}

	static public Index<ServiceReservation> buildDNA(String myKey, String serviceClass) {
		Index<ServiceReservation> myDNA = new Index<ServiceReservation>();
		buildDNA(myDNA, myKey, serviceClass, null);
		log.info("{}", myDNA.getRootNode().size());
		return myDNA;
	}

	static public Index<ServiceReservation> buildDNA(String myKey, String serviceClass, String comment) {
		Index<ServiceReservation> myDNA = new Index<ServiceReservation>();
		buildDNA(myDNA, myKey, serviceClass, comment);
		log.info("dna root node size {}", myDNA.getRootNode().size());
		return myDNA;
	}

	/**
	 * copyShallowFrom is used to help maintain state information with
	 */
	public static Object copyShallowFrom(Object target, Object source) {
		if (target == source) { // data is myself - operating on local copy
			return target;
		}

		Class<?> sourceClass = source.getClass();
		Class<?> targetClass = target.getClass();
		Field fields[] = sourceClass.getDeclaredFields();
		for (int j = 0, m = fields.length; j < m; j++) {
			try {
				Field f = fields[j];

				int modifiers = f.getModifiers();

				// if (Modifier.isPublic(mod)
				// !(Modifier.isPublic(f.getModifiers())
				// Hmmm JSON mappers do hacks to get by IllegalAccessExceptions.... Hmmmmm
				if (!Modifier.isPublic(f.getModifiers()) || f.getName().equals("log") || Modifier.isTransient(f.getModifiers()) || Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) {
					log.debug(String.format("skipping %s", f.getName()));
					continue;
				}
				Type t = f.getType();

				log.info(String.format("setting %s", f.getName()));
				/*
				if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) {
					continue;
				}
				*/

				if (t.equals(java.lang.Boolean.TYPE)) {
					targetClass.getDeclaredField(f.getName()).setBoolean(target, f.getBoolean(source));
				} else if (t.equals(java.lang.Character.TYPE)) {
					targetClass.getDeclaredField(f.getName()).setChar(target, f.getChar(source));
				} else if (t.equals(java.lang.Byte.TYPE)) {
					targetClass.getDeclaredField(f.getName()).setByte(target, f.getByte(source));
				} else if (t.equals(java.lang.Short.TYPE)) {
					targetClass.getDeclaredField(f.getName()).setShort(target, f.getShort(source));
				} else if (t.equals(java.lang.Integer.TYPE)) {
					targetClass.getDeclaredField(f.getName()).setInt(target, f.getInt(source));
				} else if (t.equals(java.lang.Long.TYPE)) {
					targetClass.getDeclaredField(f.getName()).setLong(target, f.getLong(source));
				} else if (t.equals(java.lang.Float.TYPE)) {
					targetClass.getDeclaredField(f.getName()).setFloat(target, f.getFloat(source));
				} else if (t.equals(java.lang.Double.TYPE)) {
					targetClass.getDeclaredField(f.getName()).setDouble(target, f.getDouble(source));
				} else {
					log.info(String.format("setting reference to remote object %s", f.getName()));
					targetClass.getDeclaredField(f.getName()).set(target, f.get(source));
				}
			} catch (Exception e) {
				Logging.logError(e);
			}
		}
		return target;
	}

	/**
	 * Create the reserved peer service if it has not already been created
	 * 
	 * @param key
	 *            unique identification of the peer service used by the
	 *            composite
	 * @return true if successfully created
	 */
	static public ServiceInterface createRootReserved(String key) {
		log.info(String.format("createReserved %s ", key));
		IndexNode<ServiceReservation> node = dna.getNode(key);
		if (node != null) {
			ServiceReservation r = dna.get(key);
			return Runtime.create(r.actualName, r.fullTypeName);
		}

		log.error(String.format("createRootReserved can not create %s", key));
		return null;
	}

	/**
	 * 
	 * @return
	 */
	public static String getCFGDir() {
		return cfgDir;
	}

	static public Index<ServiceReservation> getDNA() {
		return dna;
	}

	/**
	 * 
	 * @param inHost
	 * @return
	 */
	public static String getHostName(final String inHost) {
		if (inHost != null)
			return inHost;

		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			log.error("could not find host, host is null or empty !");
		}

		return "localhost"; // no network - still can't be null // chumby
	}

	// TODO !! recurse boolean
	static public Peers getLocalPeers(String name, Class<?> clazz) {
		return getLocalPeers(name, clazz.getCanonicalName());
	}

	static public Peers getLocalPeers(String name, String serviceClass) {
		String fullClassName;
		if (!serviceClass.contains(".")) {
			fullClassName = String.format("org.myrobotlab.service.%s", serviceClass);
		} else {
			fullClassName = serviceClass;
		}
		try {
			Class<?> theClass = Class.forName(fullClassName);
			Method method = theClass.getMethod("getPeers", String.class);
			Peers peers = (Peers) method.invoke(null, new Object[] { name });
			return peers;

		} catch (Exception e) {
			log.debug(String.format("%s does not have a getPeers", fullClassName));
		}

		return null;
	}

	/**
	 * 
	 * @param className
	 * @param methodName
	 * @param params
	 * @return
	 */
	public static String getMethodToolTip(String className, String methodName, Class<?>[] params) {
		Class<?> c;
		Method m;
		ToolTip tip = null;
		try {
			c = Class.forName(className);

			m = c.getMethod(methodName, params);

			tip = m.getAnnotation(ToolTip.class);
		} catch (SecurityException e) {
			logException(e);
		} catch (NoSuchMethodException e) {
			logException(e);
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			logException(e);
		}

		if (tip == null) {
			return null;
		}
		return tip.value();
	}
/*  RECENTLY MOVED TO INSTANCIATOR !!!!
	static public Object getNewInstance(Class<?> cast, String classname, Object... params) {
		return getNewInstance(new Class<?>[] { cast }, classname, params);
	}

	static public Object getNewInstance(Class<?>[] cast, String classname, Object... params) {
		try {
			return getThrowableNewInstance(cast, classname, params);
		} catch (ClassNotFoundException e) {
			// quiet no class
			log.info(String.format("class %s not found", classname));
		} catch (Exception e) {
			// noisy otherwise
			Logging.logError(e);
		}
		return null;
	}

	static public Object getNewInstance(String classname) {

		return getNewInstance((Class<?>[]) null, classname, (Object[]) null);
	}

	static public Object getNewInstance(String classname, Object... params) {
		return getNewInstance((Class<?>[]) null, classname, params);
	}

	static public Object getThrowableNewInstance(Class<?>[] cast, String classname, Object... params) throws ClassNotFoundException, NoSuchMethodException, SecurityException,
			InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		Class<?> c;	

		c = Class.forName(classname);
		if (params == null) {
			Constructor<?> mc = c.getConstructor();
			return mc.newInstance();
		} else {
			Class<?>[] paramTypes = new Class[params.length];
			for (int i = 0; i < params.length; ++i) {
				paramTypes[i] = params[i].getClass();
			}
			Constructor<?> mc = null;
			if (cast == null) {
				mc = c.getConstructor(paramTypes);
			} else {
				mc = c.getConstructor(cast);
			}
			return mc.newInstance(params); // Dynamically instantiate it
		}
	}
	*/

	/**
	 * 
	 * @param e
	 */
	public final static void logException(final Throwable e) {
		log.error(stackToString(e));
	}

	static public void logTimeEnable(Boolean b) {
		Logging.logTimeEnable(b);
	}

	/**
	 * This method will merge in the requested peer dna into the final global
	 * dna - from which it will be accessable for create methods
	 * 
	 * @param myKey
	 * @param className
	 */
	static public void mergePeerDNA(String myKey, String className) {
		buildDNA(dna, myKey, className, "merged dna");
		log.debug("merged dna \n{}", dna);
	}

	/**
	 * this is called by the framework before a new Service is constructed and
	 * places the "default" reservations of the Service's type into the global
	 * reservation index.
	 * 
	 * it "merges" in the default index, because if any user defined values are
	 * found it preserves them.
	 * 
	 * it mergest a SINGLE LEVEL - only its immediate peers (non-recursive)
	 * recursive call is not necessary, because as each service is created they
	 * will create their peers
	 * 
	 * TODO - a more detailed merge could be useful, so that a user has the
	 * option of only specifying the minimum changes to a ServiceRegistration
	 * 
	 * @param myKey
	 * @param serviceClass
	 */
	static public void mergePeerDNAx(String myKey, String className) {
		String serviceClass = className;
		if (!className.contains(".")) {
			serviceClass = String.format("org.myrobotlab.service.%s", className);
		}
		log.info(String.format("createReserves (%s, %s)", myKey, serviceClass));
		try {
			Class<?> theClass = Class.forName(serviceClass);
			Method method = theClass.getMethod("getPeers", String.class);
			Peers peers = (Peers) method.invoke(null, new Object[] { myKey });
			IndexNode<ServiceReservation> myNode = peers.getDNA().getNode(myKey);
			// LOAD CLASS BY NAME - and do a getReservations on it !
			HashMap<String, IndexNode<ServiceReservation>> peerRequests = myNode.getBranches();
			for (Entry<String, IndexNode<ServiceReservation>> o : peerRequests.entrySet()) {
				String peerKey = o.getKey();
				IndexNode<ServiceReservation> p = o.getValue();

				String fullKey = Peers.getPeerKey(myKey, peerKey);
				ServiceReservation peersr = p.getValue();
				ServiceReservation globalSr = dna.get(fullKey);

				// TODO -
				if (globalSr == null) {
					// FIXME if this method accepted an Index<?> then Peers
					// could use it
					reserveRoot(fullKey, peersr.fullTypeName, peersr.comment);
				} else {
					log.info(String.format("*found** key %s -> %s %s %s", fullKey, globalSr.actualName, globalSr.fullTypeName, globalSr.comment));
				}
			}

		} catch (Exception e) {
			log.debug(String.format("%s does not have a getPeers", serviceClass));
		}
	}

	/**
	 * Reserves a name for a root level Service. allows modifications to the
	 * reservation map at the highest level
	 * 
	 * @param key
	 * @param simpleTypeName
	 * @param comment
	 */
	static public void reserveRoot(String key, String simpleTypeName, String comment) {
		// strip delimeter out if put in by key
		// String actualName = key.replace(".", "");
		reserveRoot(key, key, simpleTypeName, comment);
	}

	static public void reserveRoot(String key, String actualName, String simpleTypeName, String comment) {
		log.info(String.format("reserved key %s -> %s %s %s", key, actualName, simpleTypeName, comment));
		dna.put(key, new ServiceReservation(key, actualName, simpleTypeName, comment));
	}
	
	/**
	 *  basic useful reset of a peer before service is created
	 * @param string
	 * @param string2
	 */
	public void setPeer(String peerName, String peerType) {
		String fullKey = String.format("%s.%s", getName(), peerName);
		ServiceReservation sr = new ServiceReservation(fullKey, peerName, peerType, null);
		dna.put(fullKey, sr);
	}

	/*
	 * static public Set<String> getDependencies(String serviceClass) {
	 * 
	 * }
	 */

	/**
	 * This method re-binds the key to another name. An example of where this
	 * would be used is within Tracking there is an Servo service named "x",
	 * however it may be desired to bind this to an already existing service
	 * named "pan" in a pan/tilt system
	 * 
	 * @param key
	 *            key internal name
	 * @param newName
	 *            new name of bound peer service
	 * @return true if re-binding took place
	 */
	static public boolean reserveRootAs(String key, String newName) {

		ServiceReservation genome = dna.get(key);
		if (genome == null) {
			// FIXME - this is a BAD KEY !!! into the ServiceReservation (I
			// think :P) - another
			// reason to get rid of it !!
			dna.put(key, new ServiceReservation(key, newName, null, null));
		} else {
			genome.actualName = newName;
		}
		return true;
	}

	public static boolean setSecurityProvider(AuthorizationProvider provider) {
		if (security != null) {
			log.error("security provider is already set - it can not be unset .. THAT IS THE LAW !!!");
			return false;
		}

		security = provider;
		return true;
	}

	/**
	 * sleep without the throw
	 * 
	 * @param millis
	 */
	public static void sleep(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			logException(e);
		}
	}

	/**
	 * 
	 * @param e
	 * @return
	 */
	public final static String stackToString(final Throwable e) {
		StringWriter sw;
		try {
			sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
		} catch (Exception e2) {
			return "bad stackToString";
		}
		return "------\r\n" + sw.toString() + "------\r\n";
	}

	// FIXME - make a static initialization part !!!

	/**
	 * 
	 * @param reservedKey
	 * @param serviceClass
	 * @param inHost
	 */
	public Service(String reservedKey) {
		super(reservedKey);

		serviceClass = this.getClass().getCanonicalName();
		simpleName = this.getClass().getSimpleName();

		// FIXME - this is 'sort-of' static :P
		if (methodSet == null) {
			methodSet = getMessageSet();
		}

		// a "safety" if Service was created by new Service(name)
		// we still want the local Runtime running
		if (!Runtime.isRuntime(this)) {
			Runtime.getInstance();
		}

		// see if incoming key is my "actual" name
		ServiceReservation sr = dna.get(reservedKey);
		if (sr != null) {
			log.info(String.format("found reservation exchanging reservedKey %s for actual name %s", reservedKey, sr.actualName));
			name = sr.actualName;
		} else {
			name = reservedKey;
		}
		// keep MessageService name in sync

		// comment ?
		mergePeerDNA(reservedKey, serviceClass);

		// this.timer = new Timer(String.format("%s_timer", name)); FIXME -
		// re-implement but only create if there is a task!!
		this.inbox = new Inbox(name);
		this.outbox = new Outbox(this);
		cm = new CommunicationManager(name);
		this.outbox.setCommunicationManager(cm);

		TSFormatter.setCalendar(cal);
		load();
		Runtime.register(this, null);
	}

	public void addListener(MRLListener listener) {
		addListener(listener.topicMethod, listener.callbackName, listener.callbackMethod);
	}

	/**
	 * adds a MRL message listener to this service this is the result of a
	 * "subscribe" from a different service FIXME !! - implement with HashMap or
	 * HashSet .. WHY ArrayList ???
	 * 
	 * @param topicMethod
	 *            - method when called, it's return will be sent to the
	 *            callbackName/calbackMethod
	 * @param callbackName
	 *            - name of the service to send return message to
	 * @param callbackMethod
	 *            - name of the method to send return data to
	 */
	public void addListener(String topicMethod, String callbackName, String callbackMethod) {
		MRLListener listener = new MRLListener(topicMethod, callbackName, callbackMethod);
		if (outbox.notifyList.containsKey(listener.topicMethod.toString())) {
			// iterate through all looking for duplicate
			boolean found = false;
			ArrayList<MRLListener> nes = outbox.notifyList.get(listener.topicMethod.toString());
			for (int i = 0; i < nes.size(); ++i) {
				MRLListener entry = nes.get(i);
				if (entry.equals(listener)) {
					log.warn(String.format("attempting to add duplicate MRLListener %s", listener));
					found = true;
					break;
				}
			}
			if (!found) {
				log.info(String.format("adding addListener from %s.%s to %s.%s", this.getName(), listener.topicMethod, listener.callbackName, listener.callbackMethod));
				nes.add(listener);
			}
		} else {
			ArrayList<MRLListener> notifyList = new ArrayList<MRLListener>();
			notifyList.add(listener);
			log.info(String.format("adding addListener from %s.%s to %s.%s", this.getName(), listener.topicMethod, listener.callbackName, listener.callbackMethod));
			outbox.notifyList.put(listener.topicMethod.toString(), notifyList);
		}
	}

	// -------------------------------- new createPeer end
	// -----------------------------------

	/**
	 * DEPRECATE - use addTask
	 * @param interval
	 * @param method
	 * @param params
	 */
	@Deprecated
	public void addLocalTask(int interval, String method, Object... params) {
		if (timer == null) {
			timer = new Timer(String.format("%s.timer", getName()));
		}

		Task task = new Task(interval, getName(), method, params);
		timer.schedule(task, 0);
	}
	
	/**
	 * a stronger bigger better task handler !
	 * @param name
	 */
	public void addTask(String name, int interval, String method, Object... params){
		Timer timer = new Timer(String.format("%s.timer", String.format("%s.%s", getName(), name)));
		Task task = new Task(interval, getName(), method, params);
		timer.schedule(task, 0);
		tasks.put(name, timer);
	}
	
	public void purgeTask(String taskName){
		if (tasks.containsKey(taskName)){
			Timer timer = tasks.get(taskName);
			if (timer != null) {
				try {
					timer.cancel();
					timer.purge();
					timer = null;
				} catch (Exception e) {
					log.info(e.getMessage());
				}
			}
		} else {
			log.warn("purgeTask - task {} does not exist", taskName);
		}
	}
	
	public void purgeTasks(){
		for (String taskName : tasks.keySet()){
			Timer timer = tasks.get(taskName);
			if (timer != null) {
				try {
					timer.cancel();
					timer.purge();
					timer = null;
				} catch (Exception e) {
					log.info(e.getMessage());
				}
			}
		}
		tasks.clear();
	}
	
	/**
	 * DEPRECATED - use purgeTasks
	 */
	@Deprecated
	public void purgeAllTasks() {
		info("purgeAllTasks");
		if (timer != null) {
			try {
				timer.cancel();
				timer.purge();
				timer = null;
			} catch (Exception e) {
				log.info(e.getMessage());
			}
		}
	}

	public boolean allowDisplay() {
		return allowDisplay;
	}

	public void allowDisplay(Boolean b) {
		allowDisplay = b;
	}

	// new state functions begin --------------------------
	public void broadcastState() {
		invoke("publishState");
	}

	public String clearLastError() {
		String le = lastError.toString();
		lastError = null;
		return le;
	}

	public void close(Writer w) {
		if (w == null) {
			return;
		}
		try {
			w.flush();
		} catch (Exception e) {
			Logging.logError(e);
		} finally {
			try {
				w.close();
			} catch (Exception e) {
				// don't really care
			}
		}

	}



	public synchronized ServiceInterface createPeer(String reservedKey) {
		String fullkey = Peers.getPeerKey(getName(), reservedKey);
		ServiceReservation sr = dna.get(fullkey);
		if (sr == null) {
			error("can not create peer from reservedkey %s - no type definition !", fullkey);
			return null;
		}
		return Runtime.create(sr.actualName, sr.fullTypeName);
	}

	// -------------------------------- new createPeer begin
	// -----------------------------------
	public synchronized ServiceInterface createPeer(String reservedKey, String defaultType) {
		return Runtime.create(Peers.getPeerKey(getName(), reservedKey), defaultType);
	}

	/**
	 * framework interface for Services which can display themselves most will
	 * not implement this method. keeps the framework display type agnostic
	 */
	public void display() {
	}

	/**
	 * called typically from a remote system When 2 MRL instances are connected
	 * they contain serialized non running Service in a registry, which is
	 * maintained by the Runtime. The data can be stale.
	 * 
	 * Messages are sometimes sent (often in the gui) which prompt the remote
	 * service to "broadcastState" a new serialized snapshot is broadcast to all
	 * subscribed methods, but there is no guarantee that the registry is
	 * updated
	 * 
	 * This method will update the registry, additionally it will block until
	 * the refresh response comes back
	 * 
	 * @return
	 */

	public Heartbeat echoHeartbeat(Heartbeat pulse) {
		return pulse;
	}

	@Override
	abstract public String[] getCategories();

	/**
	 * 
	 * @return
	 */
	public CommunicationInterface getComm() {
		return cm;
	}

	@Override
	public String[] getDeclaredMethodNames() {
		Method[] methods = getDeclaredMethods();
		String[] ret = new String[methods.length];

		log.info(String.format("getDeclaredMethodNames loading %d non-sub-routable methods", methods.length));
		for (int i = 0; i < methods.length; ++i) {
			ret[i] = methods[i].getName();
		}
		return ret;
	}

	@Override
	public Method[] getDeclaredMethods() {
		return this.getClass().getDeclaredMethods();
	}

	/*
	 * TODO - support multiple parameters Constructor c =
	 * A.class.getConstructor(new Class[]{Integer.TYPE, Float.TYPE}); A a =
	 * (A)c.newInstance(new Object[]{new Integer(1), new Float(1.0f)});
	 */
	// TODO - so now we support string constructors - it really should be any
	// params
	// TODO - without class specific parameters it will get "the real class"
	// regardless of casting

	/**
	 * Short description of the service
	 */
	@Override
	abstract public String getDescription();

	/**
	 * 
	 * @return
	 */
	public Inbox getInbox() {
		return inbox;
	}

	@Override
	public URI getInstanceId() {
		return instanceId;
	}

	/**
	 * 
	 * @return
	 */
	public String getIntanceName() {
		return name;
	}

	public Status getLastError() {
		return lastError;
	}

	// FIXME - use the method cache
	public HashSet<String> getMessageSet() {
		HashSet<String> ret = new HashSet<String>();
		Method[] methods = getMethods();
		log.info(String.format("getMessageSet loading %d non-sub-routable methods", methods.length));
		for (int i = 0; i < methods.length; ++i) {
			ret.add(methods[i].getName());
		}
		return ret;
	}

	@Override
	public String[] getMethodNames() {
		Method[] methods = getMethods();
		String[] ret = new String[methods.length];

		log.info(String.format("getMethodNames loading %d non-sub-routable methods", methods.length));
		for (int i = 0; i < methods.length; ++i) {
			ret[i] = methods[i].getName();
		}
		return ret;
	}

	@Override
	public Method[] getMethods() {
		return this.getClass().getMethods();
	}

	/**
	 * 
	 * @return
	 * @throws InterruptedException
	 */
	public Message getMsg() throws InterruptedException {
		return inbox.getMsg();
	}

	/**
	 * 
	 */
	@Override
	public ArrayList<MRLListener> getNotifyList(String key) {
		if (getOutbox() == null) {
			// this is remote system - it has a null outbox, because its
			// been serialized with a transient outbox
			// and your in a skeleton
			// use the runtime to send a message
			@SuppressWarnings("unchecked")
			// FIXME - parameters !
			ArrayList<MRLListener> remote = (ArrayList<MRLListener>) Runtime.getInstance().sendBlocking(getName(), "getNotifyList", new Object[] { key });
			return remote;

		} else {
			return getOutbox().notifyList.get(key);
		}
	}

	/**
	 * 
	 */
	@Override
	public ArrayList<String> getNotifyListKeySet() {
		ArrayList<String> ret = new ArrayList<String>();
		if (getOutbox() == null) {
			// this is remote system - it has a null outbox, because its
			// been serialized with a transient outbox
			// and your in a skeleton
			// use the runtime to send a message
			@SuppressWarnings("unchecked")
			ArrayList<String> remote = (ArrayList<String>) Runtime.getInstance().sendBlocking(getName(), "getNotifyListKeySet");
			return remote;
		} else {
			ret.addAll(getOutbox().notifyList.keySet());
		}
		return ret;
	}

	/**
	 * 
	 * @return
	 */
	public Outbox getOutbox() {
		return outbox;
	}

	public String getPeerKey(String key) {
		return Peers.getPeerKey(getName(), key);
	}

	/**
	 * a default way to attach Services to other Services An example would be
	 * attaching a Motor to a MotorControl or a Speaking service (TTS) to a
	 * Listening service (STT) such that when the system is speaking it does not
	 * try to listen & act on its own speech (feedback loop)
	 * 
	 * FIXME - the GUIService currently has attachGUI() and detachGUI() - these
	 * are to bind Services with their swing views/tab panels. It should be
	 * generalized to this attach method
	 * 
	 * @param serviceName
	 * @return if successful
	 * 
	 */

	public String getServiceResourceFile(String subpath) {
		return FileIO.resourceToString(String.format("%s/%s", this.getSimpleName(), subpath));
	}

	@Override
	public String getSimpleName() {
		return simpleName;
	}

	/**
	 * 
	 * @return
	 */
	public Thread getThisThread() {
		return thisThread;
	}

	@Override
	public String getType() {
		return getClass().getCanonicalName();
	}

	/**
	 * returns if the Service has a display - this would be any Service who had
	 * a display system GUIService (Swing) would be an example, most Services
	 * would return false keeps the framework display type agnostic
	 * 
	 * @return
	 */
	@Override
	public boolean hasDisplay() {
		return false;
	}

	public boolean hasError() {
		return lastError != null;
	}

	// TODO Clock example - roles
	// no - security (internal) Role - default access - ALLOW
	// WebGui - public - no security header - default access DISALLOW +
	// exception
	// WebGui (remote in genera) - user / group ALLOW

	/*
	 * private boolean hasAccess(Message msg) { // turn into single key ??? //
	 * type.name.method
	 * 
	 * // check this type <-- not sure i want to support this
	 * 
	 * // check this name & method // if any access limitations exist which
	 * might be applicable if (accessRules.containsKey(msg.name) ||
	 * accessRules.containsKey(String.format("%s.%s", msg.name, msg.method))) {
	 * // restricted service - check for authorization // Security service only
	 * provides authorization ? if (security == null) { return false; } else {
	 * return security.isAuthorized(msg); }
	 * 
	 * }
	 * 
	 * // invoke - SecurityException - log error return false; }
	 */

	@Override
	public boolean hasPeers() {
		try {
			Class<?> theClass = Class.forName(serviceClass);
			Method method = theClass.getMethod("getPeers", String.class);
		} catch (Exception e) {
			log.debug(String.format("%s does not have a getPeers", serviceClass));
			return false;
		}
		return true;
	}

	public String help() {
		return help("url", "declared");
	}

	public String help(String format, String level) {
		StringBuffer sb = new StringBuffer();
		Method[] methods = this.getClass().getDeclaredMethods();
		TreeMap<String, Method> sorted = new TreeMap<String, Method>();

		for (int i = 0; i < methods.length; ++i) {
			Method m = methods[i];
			sorted.put(m.getName(), m);
		}
		for (String key : sorted.keySet()) {
			Method m = sorted.get(key);
			sb.append("/").append(getName()).append("/").append(m.getName());
			Class<?>[] types = m.getParameterTypes();
			if (types != null) {
				for (int j = 0; j < types.length; ++j) {
					Class<?> c = types[j];
					sb.append("/").append(c.getSimpleName());
				}
			}
			sb.append("\n");
		}

		sb.append("\n");
		return sb.toString();
	}

	/**
	 * 
	 * @param msg
	 */
	@Override
	public void in(Message msg) {
		inbox.add(msg);
	}

	// BOXING - BEGIN --------------------------------------

	/**
	 * This is where all messages are routed to and processed
	 * 
	 * @param msg
	 * @return
	 */
	@Override
	final public Object invoke(Message msg) {
		Object retobj = null;

		if (log.isDebugEnabled()) {
			log.debug(String.format("--invoking %s.%s(%s) %s --", name, msg.method, CodecUtils.getParameterSignature(msg.data), msg.msgId));
		}

		// recently added - to support "nameless" messages - concept you may get
		// a message at this point
		// which does not belong to you - but is for a service in the same
		// Process
		// this is to support nameless Runtime messages but theoretically it
		// could
		// happen in other situations...
		if (!name.equals(msg.name)) {
			// wrong Service - get the correct one
			return Runtime.getService(msg.name).invoke(msg);
		}

		// SECURITY -
		// 0. allowing export - whether or not we'll allow services to be
		// exported - based on Type or Name
		// 1. we have firewall like rules where we can add inclusion and
		// exclusion rules - based on Type or Name - Service Level - Method
		// Level
		// 2. authentication & authorization
		// 3. transport mechanism (needs implementation on each type of remote
		// Communicator e.g. XMPP RemoteAdapter WebGui etc...)

		// check for access
		// if access FAILS ! - check for authenticated access
		// not needed "centrally" - instead will impement in Communicators
		// which hand foriegn connections
		// if (security == null || security.isAuthorized(msg)) {

		// "local" invoke - you have a "real" reference
		retobj = invokeOn(this, msg.method, msg.data);
		// }

		// retobject will be returned as another
		// message
		return retobj;
	}

	@Override
	final public Object invoke(String method) {
		return invokeOn(this, method, (Object[]) null);
	}

	@Override
	final public Object invoke(String method, Object... params) {
		return invokeOn(this, method, params);
	}

	/**
	 * the core working invoke method
	 * 
	 * @param object
	 * @param method
	 * @param params
	 * @return return object
	 */
	@Override
	final public Object invokeOn(Object obj, String method, Object... params) {

		if (obj == null) {
			log.error("invokeOn object is null");
			return null;
		}

		Object retobj = null;
		Class<?> c = null;
		Class<?>[] paramTypes = null;

		try {
			c = obj.getClass();

			if (params != null) {
				paramTypes = new Class[params.length];
				for (int i = 0; i < params.length; ++i) {
					if (params[i] != null) {
						paramTypes[i] = params[i].getClass();
					} else {
						paramTypes[i] = null;
					}
				}
			}
			Method meth = null;

			// TODO - method cache map
			// can not auto-box or downcast with this method - getMethod will
			// return a "specific & exact" match based
			// on parameter types - the thing is we may have a typed signature
			// which will allow execution - but
			// if so we need to search

			// SECURITY - ??? can't be implemented here - need a full message
			meth = c.getMethod(method, paramTypes); // getDeclaredMethod zod !!!
			retobj = meth.invoke(obj, params);

			// put return object onEvent
			out(method, retobj);
		} catch (NoSuchMethodException e) {
			
			
			// cache key  compute
			
			// TODO: validate what "params.toString()" returns.
			StringBuilder keyBuilder = new StringBuilder();
			for (Object o : paramTypes) {
				keyBuilder.append(o);
			}
			String methodCacheKey = c.toString() + "_" + keyBuilder.toString();
			Method mC = LRUMethodCache.getInstance().getCacheEntry(methodCacheKey);
			if (mC != null) {
				// We found a cached hit!  lets invoke on that.
				try {
					retobj = mC.invoke(obj, params);
					// put return object onEvent
					out(method, retobj);
					// return 
					return retobj;
				} catch (Exception e1) {
					log.error(String.format("boom goes method %s", mC.getName()));
					Logging.logError(e1);
				}

			}
			
			// TODO - build method cache map from errors
			log.warn(String.format("%s.%s NoSuchMethodException - attempting upcasting", c.getSimpleName(), MethodEntry.getPrettySignature(method, paramTypes, null)));

			// TODO - optimize with a paramter TypeConverter & Map
			// c.getMethod - returns on EXACT match - not "Working" match
			Method[] allMethods = c.getMethods(); // ouch
			log.warn(String.format("ouch! need to search through %d methods", allMethods.length));

			for (Method m : allMethods) {
				String mname = m.getName();
				if (!mname.equals(method)) {
					continue;
				}

				Type[] pType = m.getGenericParameterTypes();
				// checking parameter lengths
				if (params == null && pType.length != 0 || pType.length != params.length) {
					continue;
				}
				try {
					log.debug("found appropriate method");
					retobj = m.invoke(obj, params);
					// put return object onEvent
					out(method, retobj);
					// we've found a match. put that in the cache.
					LRUMethodCache.getInstance().addCacheEntry(methodCacheKey, m);
					return retobj;
				} catch (Exception e1) {
					log.error(String.format("boom goes method %s", m.getName()));
					Logging.logError(e1);
				}
			}

			log.error(String.format("did not find method - %s(%s)", method, CodecUtils.getParameterSignature(params)));
		} catch (InvocationTargetException e) {
			Throwable target = e.getTargetException();
			error(String.format("%s %s", target.getClass().getSimpleName(), target.getMessage()));
			Logging.logError(e);
		} catch (Exception uknown) {
			error(String.format("%s %s", uknown.getClass().getSimpleName(), uknown.getMessage()));
			Logging.logError(uknown);
		}

		return retobj;
	}

	@Override
	public boolean isLocal() {
		return instanceId == null;
	}
	
	@Override
	public boolean isRuntime(){
		return Runtime.class == this.getClass();
	}

	/**
	 * 
	 * @return
	 */
	public boolean isReady() {
		return true;
	}

	/**
	 * 
	 * @return
	 */
	public boolean isRunning() {
		return isRunning;
	}

	/**
	 * method of de-serializing default will to load simple xml from name file
	 */
	@Override
	public boolean load() {
		return load(null, null);
	}

	/**
	 * 
	 * @param o
	 * @param inCfgFileName
	 * @return
	 */
	public boolean load(Object o, String inCfgFileName) {
		String filename = null;
		if (inCfgFileName == null) {
			filename = String.format("%s%s%s.json", cfgDir, File.separator, this.getName());
		} else {
			filename = inCfgFileName;
		}
		if (o == null) {
			o = this;
		}

		try {
			File cfg = new File(filename);
			if (cfg.exists()) {
				// serializer.read(o, cfg);
				String json = FileIO.fileToString(filename);
				Object saved = CodecUtils.fromJson(json, o.getClass());
				copyShallowFrom(o, saved);
				return true;
			}
			log.info(String.format("cfg file %s does not exist", filename));
		} catch (Exception e) {
			Logging.logError(e);
		}
		return false;
	}


	/**
	 * 
	 * @param msg
	 */
	public void out(Message msg) {
		outbox.add(msg);
	}

	/**
	 * Creating a message function call - without specifying the recipients -
	 * static routes will be applied this is good for Motor drivers - you can
	 * swap motor drivers by creating a different static route The motor is not
	 * "Aware" of the driver - only that it wants to method="write" data to the
	 * driver
	 * 
	 * @param method
	 * @param o
	 */
	public void out(String method, Object o) {
		Message m = createMessage("", method, o); // create a un-named message
													// as output

		if (m.sender.length() == 0) {
			m.sender = this.getName();
		}
		if (m.sendingMethod.length() == 0) {
			m.sendingMethod = method;
		}
		outbox.add(m);
	}

	// override for extended functionality
	public boolean preProcessHook(Message m) {
		return true;
	}

	// override for extended functionality
	public boolean preRoutingHook(Message m) {
		return true;
	}

	/**
	 * framework diagnostic publishing method for examining load, capacity, and
	 * throughput of Inbox & Outbox queues
	 * 
	 * @param stats
	 * @return
	 */
	public QueueStats publishQueueStats(QueueStats stats) {
		return stats;
	}

	/**
	 * publishing point for the whole service the entire Service is published
	 * 
	 * @return
	 */
	public Service publishState() {
		return this;
	}

	

	@Override
	public void releasePeers() {
		log.info(String.format("dna - %s", dna.toString()));
		String myKey = getName();
		log.info(String.format("releasePeers (%s, %s)", myKey, serviceClass));
		try {
			Class<?> theClass = Class.forName(serviceClass);
			Method method = theClass.getMethod("getPeers", String.class);
			Peers peers = (Peers) method.invoke(null, new Object[] { myKey });
			IndexNode<ServiceReservation> myNode = peers.getDNA().getNode(myKey);
			// LOAD CLASS BY NAME - and do a getReservations on it !
			HashMap<String, IndexNode<ServiceReservation>> peerRequests = myNode.getBranches();
			for (Entry<String, IndexNode<ServiceReservation>> o : peerRequests.entrySet()) {
				String peerKey = o.getKey();
				IndexNode<ServiceReservation> p = o.getValue();

				String fullKey = Peers.getPeerKey(myKey, peerKey);
				ServiceReservation peersr = p.getValue();
				ServiceReservation globalSr = dna.get(fullKey);

				// TODO -
				if (globalSr != null) {

					log.info(String.format("*releasing** key %s -> %s %s %s", fullKey, globalSr.actualName, globalSr.fullTypeName, globalSr.comment));
					ServiceInterface si = Runtime.getService(fullKey);
					if (si == null) {
						log.info(String.format("%s is not registered - skipping", fullKey));
					} else {
						si.releasePeers();
						si.releaseService();
					}

				}
			}

		} catch (Exception e) {
			log.debug(String.format("%s does not have a getPeers", serviceClass));
		}
	}

	/**
	 * Releases resources, and unregisters service from the runtime
	 */
	@Override
	public void releaseService() {
		// note - if stopService is overwritten with extra
		// threads - releaseService will need to be overwritten too
		stopService();

		// recently added
		releasePeers();

		Runtime.release(getName());
	}

	/**
	 * 
	 */
	public void removeAllListeners() {
		outbox.notifyList.clear();
	}

	/**
	 * 
	 * @param listener
	 */
	/*
	 * DEPRECATE MRLListener Object interface - use String params public void
	 * removeListener(MRLListener listener) { if
	 * (!outbox.notifyList.containsKey(listener.outMethod.toString())) {
	 * log.error(String.format(
	 * "removeListener requested %s to be removed - but does not exist",
	 * listener)); return; } ArrayList<MRLListener> nel =
	 * outbox.notifyList.get(listener.outMethod.toString()); for (int i = 0; i <
	 * nel.size(); ++i) { MRLListener target = nel.get(i); if
	 * (target.name.compareTo(listener.name) == 0) { nel.remove(i); } } }
	 */

	/**
	 * 
	 * @param outMethod
	 * @param serviceName
	 * @param inMethod
	 * @param paramTypes
	 */
	@Override
	public void removeListener(String outMethod, String serviceName, String inMethod) {
		if (outbox.notifyList.containsKey(outMethod)) {
			ArrayList<MRLListener> nel = outbox.notifyList.get(outMethod);
			for (int i = 0; i < nel.size(); ++i) {
				MRLListener target = nel.get(i);
				if (target.callbackName.compareTo(serviceName) == 0) {
					nel.remove(i);
					log.info(String.format("removeListener requested %s.%s to be removed", serviceName, outMethod));
				}
			}
		} else {
			log.error(String.format("removeListener requested %s.%s to be removed - but does not exist", serviceName, outMethod));
		}
	}

	// ---------------- logging end ---------------------------

	@Override
	public boolean requiresSecurity() {
		return security != null;
	}

	/**
	 * Reserves a name for a Peer Service. This is important for services which
	 * control other services. Internally composite services will use a key so
	 * the name of the peer service can change, effectively binding a new peer
	 * to the composite
	 * 
	 * @param key
	 *            internal key name of peer service
	 * @param simpleTypeName
	 *            type of service
	 * @param comment
	 *            comment detailing the use of the peer service within the
	 *            composite
	 */
	public void reserve(String key, String simpleTypeName, String comment) {
		// creating
		String peerKey = getPeerKey(key);
		reserveRoot(peerKey, simpleTypeName, comment);
	}

	public void reserve(String key, String actualName, String simpleTypeName, String comment) {
		// creating
		String peerKey = getPeerKey(key);
		reserveRoot(peerKey, actualName, simpleTypeName, comment);
	}

	@Override
	final public void run() {
		isRunning = true;

		try {
			while (isRunning) {
				// TODO should this declaration be outside the while loop? if
				// so, make sure to release prior to continue
				Message m = getMsg();

				if (!preRoutingHook(m)) {
					continue;
				}

				// nameless Runtime messages
				if (m.name == null) {
					// don't know if this is "correct"
					// but we are substituting the Runtime name as soon as we
					// see that its a null
					// name message
					m.name = Runtime.getInstance().getName();
				}

				// route if necessary
				if (!m.getName().equals(this.getName())) // && RELAY
				{
					outbox.add(m); // RELAYING
					continue; // sweet - that was a long time coming fix !
				}

				if (!preProcessHook(m)) {
					// if preProcessHook returns false
					// the message does not need to continue
					// processing
					continue;
				}
				// TODO should this declaration be outside the while loop?
				Object ret = invoke(m);
				if (Message.BLOCKING.equals(m.status)) {
					// TODO should this declaration be outside the while loop?
					// create new message reverse sender and name set to same
					// msg id
					Message msg = createMessage(m.sender, m.method, ret);
					msg.sender = this.getName();
					msg.msgId = m.msgId;
					// msg.status = Message.BLOCKING;
					msg.status = Message.RETURN;

					outbox.add(msg);
				}
			}
		} catch (InterruptedException edown) {
			info("shutting down");
		} catch (Exception e) {
			error(e);
		}
	}

	/**
	 * method of serializing default will be simple xml to name file
	 */
	@Override
	public boolean save() {

		try {
			File cfg = new File(String.format("%s%s%s.json", cfgDir, File.separator, getName()));
			// serializer.write(this, cfg);
			info("saving %s", cfg.getName());

			if (this instanceof Runtime) {
				info("we cant serialize runtime yet");
				return false;
			}

			String s = CodecUtils.toJson(this);
			FileOutputStream out = new FileOutputStream(cfg);
			out.write(s.getBytes());
			out.close();
		} catch (Exception e) {
			Logging.logError(e);
			return false;
		}
		return true;
	}

	/**
	 * 
	 * @param o
	 * @param cfgFileName
	 * @return
	 */
	public boolean save(Object o, String cfgFileName) {

		try {
			File cfg = new File(String.format("%s%s%s", cfgDir, File.separator, cfgFileName));
			String s = CodecUtils.toJson(o);
			FileOutputStream out = new FileOutputStream(cfg);
			out.write(s.getBytes());
			out.close();
		} catch (Exception e) {
			Logging.logError(e);
			return false;
		}
		return true;
	}

	/**
	 * 
	 * @param cfgFileName
	 * @param data
	 * @return
	 */
	public boolean save(String cfgFileName, String data) {
		// saves user data in the .myrobotlab directory
		// with the file naming convention of name.<cfgFileName>
		try {
			FileIO.stringToFile(String.format("%s%s%s.%s", cfgDir, File.separator, this.getName(), cfgFileName), data);
		} catch (Exception e) {
			Logging.logError(e);
			return false;
		}
		return true;
	}

	/**
	 * 0?
	 * 
	 * @param name
	 * @param method
	 */
	public void send(String name, String method) {
		send(name, method, (Object[]) null);
	}

	/**
	 * boxing - the right way - thank you Java 5
	 * 
	 * @param name
	 * @param method
	 * @param data
	 */
	public void send(String name, String method, Object... data) {
		Message msg = createMessage(name, method, data);
		msg.sender = this.getName();
		// All methods which are invoked will
		// get the correct sendingMethod
		// here its hardcoded
		msg.sendingMethod = "send";

		if (recorder != null) {
			try {
				recorder.write(msg);
			} catch (IOException e) {
				logException(e);
			}
		}
		outbox.add(msg);
	}

	/**
	 * this send forces remote connect - for registering services
	 * 
	 * @param url
	 * @param method
	 * @param param1
	 */
	public void send(URI url, String method, Object param1) {
		Object[] params = new Object[1];
		params[0] = param1;
		Message msg = createMessage(name, method, params);
		outbox.getCommunicationManager().send(url, msg);
	}

	/**
	 * 
	 * @param name
	 * @param method
	 * @param data
	 * @return
	 */
	public Object sendBlocking(String name, Integer timeout, String method, Object... data) {
		Message msg = createMessage(name, method, data);
		msg.sender = this.getName();
		msg.status = Message.BLOCKING;
		msg.msgId = Runtime.getUniqueID();

		Object[] returnContainer = new Object[1];
		/*
		 * if (inbox.blockingList.contains(msg.msgID)) { log.error("DUPLICATE");
		 * }
		 */
		inbox.blockingList.put(msg.msgId, returnContainer);

		try {
			// block until message comes back
			synchronized (returnContainer) {
				outbox.add(msg);
				returnContainer.wait(timeout); // NEW !!! TIMEOUT !!!!
			}
		} catch (InterruptedException e) {
			logException(e);
		}

		return returnContainer[0];
	}

	// BOXING - End --------------------------------------
	public Object sendBlocking(String name, String method) {
		return sendBlocking(name, method, (Object[]) null);
	}

	public Object sendBlocking(String name, String method, Object... data) {
		return sendBlocking(name, 1000, method, data); // default 1 sec timeout
														// - TODO - make
														// configurable
	}

	@Override
	public void setInstanceId(URI uri) {
		instanceId = uri;
	}

	/*
	 * static public setErrorEmail(String to, String host, String user, String
	 * password){
	 * 
	 * }
	 */

	public String setLogLevel(String level) {
		Logging logging = LoggingFactory.getInstance();
		logging.setLevel(this.getClass().getCanonicalName(), level);
		return level;
	}

	/**
	 * rarely should this be used.
	 * Gateways use it to provide x-route natting services by
	 * re-writing names with prefixes
	 * @param name
	 */
	
	@Override
	public void setName(String name) {
		//this.name = String.format("%s%s", prefix, name);
		this.name = name;
	}

	@Override
	public String getName() {
		return name;
	}

	
	/**
	 * 
	 * @param s
	 * @return
	 */
	public Service setState(Service s) {
		return (Service) copyShallowFrom(this, s);
	}

	/**
	 * 
	 * @param thisThread
	 */
	public void setThisThread(Thread thisThread) {
		this.thisThread = thisThread;
	}

	public void startHeartbeat() {
		// getComm().
	}

	public ServiceInterface startPeer(String reservedKey) {
		ServiceInterface si = null;
		try {
			si = createPeer(reservedKey);
			if (si == null) {
				error("could not create service from key %s", reservedKey);
				return null;
			}

			si.startService();
		} catch (Exception e) {
			error(e.getMessage());
			Logging.logError(e);
		}
		return si;
	}

	public ServiceInterface startPeer(String reservedKey, String defaultType) throws Exception {
		ServiceInterface si = createPeer(reservedKey, defaultType);
		if (si == null) {
			error("could not create service from key %s", reservedKey);
		}

		si.startService();
		return si;
	}

	public void startRecording() {
		invoke("startRecording", new Object[] { null });
	}

	@Override
	public void startService() {
		if (!isRunning()) {
			outbox.start();
			if (thisThread == null) {
				thisThread = new Thread(this, name);
			}
			thisThread.start();
			isRunning = true;
		} else {
			log.debug("startService request: service {} is already running", name);
		}
	}

	public void stopHeartbeat() {
	}

	public void stopMsgRecording() {
		log.info("stopped recording");
		if (recorder != null) {
			try {
			recorder.stop();
			} catch(Exception e){
				Logging.logError(e);
			}
		}
	}

	/**
	 * Stops the service. Stops threads.
	 */
	@Override
	public void stopService() {
		isRunning = false;
		outbox.stop();
		if (thisThread != null) {
			thisThread.interrupt();
		}
		thisThread = null;

		save();
	}

	// -------------- Messaging Begins -----------------------
	public void subscribe(NameProvider topicName, String topicMethod) {
		String callbackMethod = CodecUtils.getCallBackName(topicMethod);
		subscribe(topicName.getName(), topicMethod, getName(), callbackMethod);
	}

	public void subscribe(String topicName, String topicMethod) {
		String callbackMethod = CodecUtils.getCallBackName(topicMethod);
		subscribe(topicName, topicMethod, getName(), callbackMethod);
	}

	public void subscribe(String topicName, String topicMethod, String callbackName, String callbackMethod) {
		log.info(String.format("subscribe [%s/%s ---> %s/%s]", topicName, topicMethod, callbackName, callbackMethod));
		MRLListener listener = new MRLListener(topicMethod, callbackName, callbackMethod);
		cm.send(createMessage(topicName, "addListener", listener));
	}

	public void unsubscribe(NameProvider topicName, String topicMethod) {
		String callbackMethod = CodecUtils.getCallBackName(topicMethod);
		subscribe(topicName.getName(), topicMethod, getName(), callbackMethod);
	}

	public void unsubscribe(String topicName, String topicMethod) {
		String callbackMethod = CodecUtils.getCallBackName(topicMethod);
		unsubscribe(topicName, topicMethod, getName(), callbackMethod);
	}

	public void unsubscribe(String topicName, String topicMethod, String callbackName, String callbackMethod) {
		log.info(String.format("subscribe [%s/%s ---> %s/%s]", topicName, topicMethod, callbackName, callbackMethod));
		/*
		 * wa deprecated MRLListener listener = new MRLListener(topicMethod,
		 * callbackName, callbackMethod); cm.send(createMessage(topicName,
		 * "removeListener", listener));
		 */
		cm.send(createMessage(topicName, "removeListener", new Object[] { topicMethod, callbackName, callbackMethod }));
	}

	// TODO - remove or reconcile - RemoteAdapter and Service are the only ones
	// using this
	/**
	 * 
	 * @param name
	 * @param method
	 * @param data
	 * @return
	 */
	public Message createMessage(String name, String method, Object data) {
		if (data == null) {
			return createMessage(name, method, null);
		}
		Object[] d = new Object[1];
		d[0] = data;
		return createMessage(name, method, d);
	}

	// FIXME All parameter constructor
	// TODO - Probably simplyfy to take array of object
	/**
	 * 
	 * @param name
	 * @param method
	 * @param data
	 * @return
	 */
	public Message createMessage(String name, String method, Object[] data) {
		Message msg = new Message();
		msg.name = name; // destination instance name
		msg.sender = this.getName();
		msg.data = data;
		msg.method = method;

		return msg;
	}

	// -------------- Messaging Ends -----------------------
	// ---------------- Status processing begin ------------------
	public Status error(Exception e) {
		Status ret = Status.error(e);
		ret.name = getName();
		invoke("publishStatus", ret);
		return ret;
	}

	@Override
	public Status error(String format, Object... args) {
		Status ret = Status.error(String.format(format, args));
		ret.name = getName();
		invoke("publishStatus", ret);
		return ret;
	}

	public Status warn(String msg) {
		Status ret = Status.warn(msg);
		invoke("publishStatus", ret);
		return ret;
	}

	@Override
	public Status warn(String format, Object... args) {
		return Status.warn(format, args);
	}

	/**
	 * set status broadcasts an info string to any subscribers
	 * 
	 * @param msg
	 */
	public String info(String msg) {
		Status status = Status.info(msg);
		invoke("publishStatus", status);
		return msg;
	}

	/**
	 * set status broadcasts an formatted info string to any subscribers
	 * 
	 * @param msg
	 */
	@Override
	public Status info(String format, Object... args) {
		return Status.info(format, args);
	}

	/**
	 * error only channel publishing point versus publishStatus which handles
	 * info, warn & error
	 * 
	 * @param msg
	 * @return
	 */
	public Status publishError(Status status) {
		return status;
	}

	public Status publishStatus(Status status) {
		status.name = getName();
		if (status.level.equals(StatusLevel.ERROR)) {
			lastError = status;
			log.error(status.toString());
			invoke("publishError", status);
		} else {
			log.info(status.toString());
		}
		return status;
	}

	// ---------------- Status processing end ------------------
	@Override
	public String toString() {
		return getName();
	}
	
	public Map<String, MethodEntry> getMethodMap() {
		return Runtime.getMethodMap(getName());
	}
	
	@Override
	public void updateStats(QueueStats stats) {
		invoke("publishStats", stats);		
	}

	@Override
	public QueueStats publishStats(QueueStats stats) {
		// log.error(String.format("===stats - dequeued total %d - %d bytes in %d ms %d Kbps", stats.total, stats.interval, stats.ts - stats.lastTS, 8 * stats.interval/ (stats.delta)));
		return stats;
	}


}
