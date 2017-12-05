package org.hobbit.benchmark.faceted_browsing.main;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.hobbit.core.Constants;
import org.hobbit.core.config.ConfigGson;
import org.hobbit.core.config.ConfigRabbitMqConnectionFactory;
import org.hobbit.core.config.RabbitMqFlows;
import org.hobbit.core.config.SimpleReplyableMessage;
import org.hobbit.core.service.docker.DockerService;
import org.hobbit.core.service.docker.DockerServiceBuilder;
import org.hobbit.core.service.docker.DockerServiceBuilderDockerClient;
import org.hobbit.core.service.docker.DockerServiceBuilderFactory;
import org.hobbit.core.service.docker.DockerServiceBuilderJsonDelegate;
import org.hobbit.core.service.docker.DockerServiceManagerClientComponent;
import org.hobbit.core.service.docker.DockerServiceManagerServerComponent;
import org.hobbit.qpid.v7.config.ConfigQpidBroker;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.google.common.util.concurrent.Service;
import com.google.gson.Gson;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.PortBinding;

import io.reactivex.Flowable;

public class TestDockerCommunication {
	
	protected static final String commandExchange = Constants.HOBBIT_COMMAND_EXCHANGE_NAME;

	public static class CommonContext {
		@Bean
		public Connection connection(ConnectionFactory connectionFactory) throws IOException, TimeoutException {
//			System.out.println("[STATUS] Creating connection from ConnectionFactory " + connectionFactory);
			Connection result = connectionFactory.newConnection();
//			result.addShutdownListener((t) -> { System.out.println("[STATUS] Closing connection from ConnectionFactory " + connectionFactory); });
			return result;
		}

		@Bean(destroyMethod="close")
		public Channel channel(Connection connection) throws IOException {
//			System.out.println("[STATUS] Creating channel from Connection " + connection);
			Channel result = connection.createChannel();
			result.addShutdownListener((t) -> {
				System.out.println("[STATUS] Closing channel " + result + "[" + result.hashCode() + "] from Connection " + connection + " " + connection.hashCode());
			});
			return result;
		}

		@Bean
		public Subscriber<ByteBuffer> commandChannel(Channel channel) throws IOException {
			return RabbitMqFlows.createFanoutSender(channel, commandExchange, null);		
		}

		@Bean
		public Flowable<ByteBuffer> commandPub(Channel channel) throws IOException {
			return RabbitMqFlows.createFanoutReceiver(channel, commandExchange, "common");		
		}
	}
	
	@Import(CommonContext.class)
	public static class ServerContext {


		@Bean
		public Flowable<SimpleReplyableMessage<ByteBuffer>> dockerServiceManagerServerConnection(Channel channel) throws IOException, TimeoutException {
			return RabbitMqFlows.createReplyableFanoutReceiver(channel, commandExchange, "server");
					//.doOnNext(x -> System.out.println("[STATUS] Received request; " + Arrays.toString(x.getValue().array()) + " replier: " + x.getReplyConsumer()));
		}

		@Bean
		public Service dockerServiceManagerServer(
			//Supplier<? extends DockerServiceBuilder<? extends DockerService>> delegateSupplier,
			@Qualifier("commandChannel") Subscriber<ByteBuffer> commandChannel,
			@Qualifier("commandPub") Flowable<ByteBuffer> commandPublisher,
			@Qualifier("dockerServiceManagerServerConnection") Flowable<SimpleReplyableMessage<ByteBuffer>> requestsFromClients,
			Gson gson
		) throws DockerCertificateException {
	        DockerClient dockerClient = DefaultDockerClient.fromEnv().build();


//		        DefaultDockerClient.builder().s

	        // Bind container port 443 to an automatically allocated available host
	        // port.
	        String[] ports = { "80", "22" };
	        Map<String, List<PortBinding>> portBindings = new HashMap<>();
	        for (String port : ports) {
	            List<PortBinding> hostPorts = new ArrayList<>();
	            hostPorts.add(PortBinding.of("0.0.0.0", port));
	            portBindings.put(port, hostPorts);
	        }

	        List<PortBinding> randomPort = new ArrayList<>();
	        randomPort.add(PortBinding.randomPort("0.0.0.0"));
	        portBindings.put("443", randomPort);

	        HostConfig hostConfig = HostConfig.builder().portBindings(portBindings).build();
	        ContainerConfig.Builder containerConfigBuilder = ContainerConfig.builder()
	                .hostConfig(hostConfig);

	        
	        // Create a supplier that yields preconfigured builders
	        Supplier<DockerServiceBuilder<? extends DockerService>> builderSupplier = () -> {
	        	DockerServiceBuilderDockerClient dockerServiceBuilder = new DockerServiceBuilderDockerClient();

		        dockerServiceBuilder
		        		.setDockerClient(dockerClient)
		        		.setContainerConfigBuilder(containerConfigBuilder);
		        
		        return dockerServiceBuilder;
	        };
	        
	        DockerServiceManagerServerComponent result =
	        		new DockerServiceManagerServerComponent(
	        				builderSupplier,
	        				commandChannel,
	        				commandPublisher,
	        				requestsFromClients,
	        				gson        				
	        				);
	        result.startAsync().awaitRunning();

	        return result;
		}
		
	}
	
	@Import(CommonContext.class)
	public static class ClientContext {
		
		
		@Bean
		public Function<ByteBuffer, CompletableFuture<ByteBuffer>> dockerServiceManagerClientConnection(Channel channel) throws IOException, TimeoutException {
			return RabbitMqFlows.createReplyableFanoutSender(channel, commandExchange, "dockerServiceManagerClientComponent", null);
		}

		
		@Bean(initMethod="startUp", destroyMethod="shutDown")
		public DockerServiceManagerClientComponent client(
				@Qualifier("commandPub") Flowable<ByteBuffer> commandPublisher,
				@Qualifier("dockerServiceManagerClientConnection") Function<ByteBuffer, CompletableFuture<ByteBuffer>> requestToServer,
				Gson gson

				) {
			DockerServiceManagerClientComponent result =
					new DockerServiceManagerClientComponent(
						commandPublisher,
						requestToServer,
						gson
					);
			
			return result;
		}

		@Bean
		public DockerServiceBuilderFactory<?> dockerServiceManagerClient(
				DockerServiceManagerClientComponent client
		) throws Exception {
			
			DockerServiceBuilderFactory<DockerServiceBuilder<DockerService>> result =
					() -> DockerServiceBuilderJsonDelegate.create(client::create);

			return result;
		}
	}

	public static class AppContext {
		@Bean
		public ApplicationRunner appRunner(DockerServiceBuilderFactory<?> clientFactory) {
			return (args) -> {
				
				DockerServiceBuilder<?> client = clientFactory.get();
				//client.setImageName("library/alpine"); 
				client.setImageName("tenforce/virtuoso");
				DockerService service = client.get();
				service.startAsync().awaitRunning();
		
				System.out.println("[STATUS] Service is running: " + service.getContainerId());
				
				//Thread.sleep(600000);
				
				System.out.println("[STATUS] Waiting for termination");
				service.stopAsync().awaitTerminated();
				System.out.println("[STATUS] Terminated");
			};
		}
	}
	
	@Test
	public void testDockerCommunication() {
		
		// NOTE The broker shuts down when the context is closed
		
		SpringApplicationBuilder builder = new SpringApplicationBuilder()
				.sources(ConfigQpidBroker.class)
				.sources(ConfigGson.class)
				.sources(ConfigRabbitMqConnectionFactory.class)
					.child(ServerContext.class)
					.sibling(ClientContext.class, AppContext.class);

		try(ConfigurableApplicationContext ctx = builder.run()) {
		} catch(Exception e) {
			System.out.println("[STATUS] Exception caught");
			throw new RuntimeException(e);
		}
	}
}