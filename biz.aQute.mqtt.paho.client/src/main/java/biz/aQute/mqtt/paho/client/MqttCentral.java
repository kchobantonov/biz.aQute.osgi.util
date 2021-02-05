package biz.aQute.mqtt.paho.client;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.util.Strings;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.lib.exceptions.Exceptions;
import aQute.lib.io.IO;
import aQute.lib.json.JSONCodec;
import biz.aQute.broker.api.Subscriber;

@Component(service = MqttCentral.class, immediate = true)
public class MqttCentral {
	Logger					log		= LoggerFactory.getLogger("biz.aQute.mqtt.paho");
	final static JSONCodec	codec	= new JSONCodec();
	final static Method		receive;
	static {
		try {
			receive = Subscriber.class.getMethod("receive", Object.class);
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
			throw Exceptions.duck(e);
		}

	}
	// guard
	final Object					lock				= new Object();
	final Map<URI, Promise<Client>>	clients				= new HashMap<>();
	final PromiseFactory			promiseFactory		= new PromiseFactory(null);
	// all access guarded by MqttCentral.lock
	int								openClients			= 0;
	long							connectionTimeout	= 30000;

	// all access guarded by MqttCentral.lock
	class Client {
		final URI			uri;
		final MqttClient	client;
		final Set<Object>	owners	= new HashSet<>();
		final String		uuid	= UUID.randomUUID().toString();

		// all access guarded by MqttCentral.lock
		Client(URI uri) {
			try {
				String clientId = uri.getUserInfo();
				if (Strings.isEmpty(clientId))
					clientId = uuid;
				this.uri = uri;
				// TODO persistence
				this.client = new MqttClient(uri.toString(), clientId, new MemoryPersistence());
				MqttConnectOptions options = new MqttConnectOptions();
				options.setAutomaticReconnect(true);
				options.setCleanSession(false);
				client.connect(options);
				System.out.println("connected " + client.isConnected() + " " + client.getCurrentServerURI());
				openClients++;
			} catch (MqttException e) {
				e.printStackTrace();
				throw Exceptions.duck(e);
			}
		}

		// all access guarded by MqttCentral.lock
		boolean remove(Object owner) {
			owners.remove(owner);
			if (owners.isEmpty()) {
				System.out.println("clients empty");
				clients.remove(uri);
				promiseFactory.submit(() -> {
					try {
						client.disconnectForcibly();
						client.close(true);
						openClients--;
						lock.notifyAll();
						System.out.println("closed");
					} catch (MqttException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return null;
				});
				return true;
			}
			return false;
		}
	}

	@Deactivate
	void deactivate() {
		clients.values().forEach(p -> p.onSuccess(c -> IO.close(c.client)));
	}

	Promise<MqttClient> getClient(Object owner, URI uri) {

		synchronized (lock) {

			Promise<Client> client = clients.get(uri);
			if (client == null) {
				client = promiseFactory.submit(() -> new Client(uri));
				clients.put(uri, client);
			}

			return client.map(c -> c.client);
		}
	}

	void bye(Object owner) {
		synchronized (lock) {
			Collection<Promise<Client>> values = new HashSet<>(clients.values());
			values.forEach(p -> p.onSuccess(c -> {
				synchronized (lock) {
					c.remove(owner);
				}
			}));
		}
	}
	
	void sync() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10000;
		synchronized(lock) {
			while( openClients > 0 && System.currentTimeMillis() < deadline) {
				lock.wait(100);
				
			}
		}
	}

}
