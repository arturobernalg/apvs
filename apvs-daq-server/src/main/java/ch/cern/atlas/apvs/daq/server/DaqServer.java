package ch.cern.atlas.apvs.daq.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.cern.atlas.apvs.db.Database;
import ch.cern.atlas.apvs.domain.Device;
import ch.cern.atlas.apvs.domain.Event;
import ch.cern.atlas.apvs.domain.InetAddress;
import ch.cern.atlas.apvs.domain.Intervention;
import ch.cern.atlas.apvs.domain.MacAddress;
import ch.cern.atlas.apvs.domain.Measurement;
import ch.cern.atlas.apvs.domain.SortOrder;
import ch.cern.atlas.apvs.ptu.server.JsonMessageDecoder;
import ch.cern.atlas.apvs.ptu.server.JsonMessageEncoder;
import ch.cern.atlas.apvs.ptu.server.MessageToBus;
import ch.cern.atlas.apvs.ptu.server.RemoveDelimiterDecoder;
import ch.cern.atlas.apvs.server.ServerStorage;

import com.google.gwt.event.shared.EventBus;
import com.google.gwt.event.shared.SimpleEventBus;

public class DaqServer {
	private Logger log = LoggerFactory.getLogger(getClass().getName());

	private int inPort;
	private int outPort;
	private int filterPort;

	private Database database;
	private Device system;
	private final static String systemDeviceName = "apvs-daq-server";
	private ServerStorage config;

	public DaqServer() {
		config = ServerStorage.getLocalStorageIfSupported();
		this.inPort = config.getInt("APVS.daq.inport");
		this.outPort = config.getInt("APVS.daq.outport");
		this.filterPort = config.getInt("APVS.daq.filterport");
	}

	public void run() {		
		database = Database.getInstance();

		final Map<String, Device> devices = database.getDeviceMap();
		Map<Device, Map<String, List<Measurement>>> lastMeasurements = database
				.getLastMeasurements(2);

		system = devices.get(systemDeviceName);
		if (system == null) {
			system = new Device(systemDeviceName,
					InetAddress.getByName("localhost"), "APVS DAQ Server",
					new MacAddress("00:00:00:00:00:00"), "apvs-daq-server", true);
			devices.put(system.getName(), system);
			database.saveOrUpdate(system);
		}

		Event event = new Event(system, "daq", "server_start", new Date());

		database.saveOrUpdate(event);

		List<Intervention> interventions = database.getList(Intervention.class,
				0, 4,
				Database.getOrder(Arrays.asList(new SortOrder("startTime"))));
		log.info("Found " + interventions.size() + " interventions");
		log.info("Found " + database.getCount(Intervention.class)
					+ " total interventions");
		
		final EventBus bus = new SimpleEventBus();

		// Configure the server.
		EventLoopGroup binGroup = new NioEventLoopGroup();
		EventLoopGroup boutGroup = new NioEventLoopGroup();
		EventLoopGroup winGroup = new NioEventLoopGroup();
		EventLoopGroup woutGroup = new NioEventLoopGroup();
		try {
			ServerBootstrap bin = new ServerBootstrap();
			bin.group(binGroup, winGroup);
			bin.channel(NioServerSocketChannel.class);

			bin.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) throws Exception {
					ch.pipeline().addLast(new IdleStateHandler(60, 30, 0));
					ch.pipeline().addLast(new RemoveDelimiterDecoder());
					ch.pipeline().addLast(new JsonMessageDecoder(devices));
					ch.pipeline().addLast(new JsonMessageEncoder());
					ch.pipeline().addLast(new MessageToBus("IN", bus));
				}
			});

			bin.option(ChannelOption.SO_BACKLOG, 128);
			bin.childOption(ChannelOption.SO_KEEPALIVE, true);

			final ChannelFuture fin = bin.bind(new InetSocketAddress(inPort))
					.sync();

			ServerBootstrap bout = new ServerBootstrap();
			bout.group(boutGroup, woutGroup);
			bout.channel(NioServerSocketChannel.class);

			bout.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) throws Exception {
					ch.pipeline().addLast(new IdleStateHandler(60, 30, 0));
					ch.pipeline().addLast(new RemoveDelimiterDecoder());
					ch.pipeline().addLast(new JsonMessageDecoder(devices));
					ch.pipeline().addLast(new JsonMessageEncoder());
					ch.pipeline().addLast(new MessageToBus("OUT", bus));
				}
			});

			bout.option(ChannelOption.SO_BACKLOG, 128);
			bout.childOption(ChannelOption.SO_KEEPALIVE, true);

			final ChannelFuture fout = bout
					.bind(new InetSocketAddress(outPort)).sync();

			log.info("DaqServer in: " + inPort + " out: " + outPort);

			// debug the bus...
			new DebugHandler(bus);
			
			new FilterHandler(bus, devices, filterPort);

			new DatabaseWriter(bus);

			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

				@Override
				public void run() {
					log.info("Shutting down");
					fin.cancel(true);
					fout.cancel(true);

					database.saveOrUpdate(new Event(system, "daq",
							"server_stop", new Date()));
					database.close();
				}
			}));

			fin.channel().closeFuture().sync();
			fout.channel().closeFuture().sync();
		} catch (InterruptedException e) {
			log.error("EX: " + e);
			e.printStackTrace();
		} finally {
			winGroup.shutdownGracefully();
			binGroup.shutdownGracefully();
			woutGroup.shutdownGracefully();
			boutGroup.shutdownGracefully();
		}
	}

	public static void main(String[] args) {
		new DaqServer().run();
	}
}
