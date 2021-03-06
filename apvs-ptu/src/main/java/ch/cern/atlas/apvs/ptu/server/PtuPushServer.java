package ch.cern.atlas.apvs.ptu.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PtuPushServer {

	private static Logger log = LoggerFactory.getLogger(PtuPushServer.class.getName());

	private final String host;
	private final int port;
	private final int refresh;
	private final String[] ids;

	public PtuPushServer(String host, int port, int refresh, String[] ids) {
		this.host = host;
		this.port = port;
		this.refresh = refresh;
		this.ids = ids;
		
		log.info("Host: "+host+":"+port+" refresh: "+(refresh/1000)+"s");
	}

	public void run() throws IOException {
		// Configure the client.
		Bootstrap bootstrap = new Bootstrap();
		bootstrap.channel(NioSocketChannel.class);

		PtuPushHandler handler = new PtuPushHandler(bootstrap, ids, refresh);

		EventLoopGroup group = new NioEventLoopGroup();

		bootstrap.group(group);
		bootstrap.handler(new PtuChannelInitializer(new PtuServerHandler(
				refresh, ids), null, true));

		// Start the connection attempt.
		handler.connect(new InetSocketAddress(host, port));
	}

	public static void main(String[] args) throws Exception {
		// Print usage if no argument is specified.
		if (args.length != 2) {
			log.info("Usage: " + PtuPushServer.class.getSimpleName()
					+ " <host> <port> <refresh>");
			return;
		}

		// Parse options.
		String host = args[0];
		int port = Integer.parseInt(args[1]);
		int refresh = Integer.parseInt(args[2]);

		new PtuPushServer(host, port, refresh, null).run();
	}
}
