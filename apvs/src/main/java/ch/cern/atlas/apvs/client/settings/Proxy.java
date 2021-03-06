package ch.cern.atlas.apvs.client.settings;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.google.gwt.user.client.rpc.IsSerializable;

/*
 * Mark Donszelmann
 */
public class Proxy implements Serializable, IsSerializable {

	private static final long serialVersionUID = -5168101174519579348L;
	private List<String> forward = new ArrayList<String>();
	private List<String> reverse = new ArrayList<String>();
	private boolean active;
	private String prefix;

	public Proxy() {
		this(true, "");
	}

	public Proxy(boolean active, String prefix) {
		this.active = active;
		this.prefix = prefix;
	}
	
	public boolean isActive() {
		return active;
	}

	public void put(String src, String dst) {
		forward.add(prefix+src);
		reverse.add(dst);
	}

	public String getUrl(String url) {
		return translate(url, forward, reverse);
	}

	public String getReverseUrl(String url) {
		return translate(url, reverse, forward);
	}

	private String translate(String url, List<String> src, List<String> dst) {
		if (!active) {
			return url;
		}

		for (int index = 0; index < src.size(); index++) {
			String find = src.get(index);
			if (url.startsWith(find)) {
				return dst.get(index) + url.substring(find.length());
			}
		}
		return url;
	}
	
	public String toString() {
		StringBuffer b = new StringBuffer("Proxy: \n");
		for (int i=0; i<forward.size(); i++) {
			b.append("   ");
			b.append(forward.get(i));
			b.append(" <-> ");
			b.append(reverse.get(i));
			b.append("\n");
		}
		return b.toString();
	}

}
