package ch.cern.atlas.apvs.ptu.server;

import java.util.Date;

import ch.cern.atlas.apvs.domain.Measurement;

@SuppressWarnings("serial")
public class Temperature extends Measurement {

	public Temperature(String ptuId, double value, Date d) {
		super(ptuId, "Temperature", value, 15.0, 40.0, "&deg;C", 15000, d);
	}
	
}
