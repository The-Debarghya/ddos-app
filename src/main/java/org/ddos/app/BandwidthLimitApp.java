/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ddos.app;

import org.onlab.rest.BaseResource;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.device.PortStatistics;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;

@Component(immediate = true)
public class BandwidthLimitApp extends BaseResource {

    private static final long BANDWIDTH_LIMIT = 21474836480L; // Bandwidth limit in bits per second (20Gbps)
    private static final long PORT_DOWN_TIME = 3600000; // Port downtime in milliseconds
    private static Logger log = LoggerFactory.getLogger(BandwidthLimitApp.class);

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    private final DeviceAdminService deviceService;
    private final Timer timer;

    public BandwidthLimitApp() {
        deviceService = get(DeviceAdminService.class);
        timer = new Timer();
    }

    @Activate
    public void activate() {
        // Start monitoring the port bandwidth
        coreService.registerApplication("org.ddos.app");
        log.info("Started");
        startMonitoring();
    }

    @Deactivate
    public void deactivate() {
        // Stop monitoring and cleanup resources
        log.info("Stopped");
        stopMonitoring();
    }

    private void startMonitoring() {
        Iterable<Device> devices = deviceService.getDevices();
        for (Device device : devices) {
            Iterable<Port> ports = deviceService.getPorts(device.id());
            for (Port port : ports) {
                if (port.number().equals(PortNumber.LOCAL)) {
                    continue;
                }
                String str = String.format("Monitoring Device:%s, Port:%s", device.id().toString(), port.number().toString());
                log.info(str);
                timer.schedule(new BandwidthCheckTask(device.id(), port.number()), 0, 1000); // Check bandwidth every second

            }
        }
    }

    private void stopMonitoring() {
        timer.cancel();
    }

    private class BandwidthCheckTask extends TimerTask {

        private final DeviceId deviceId;
        private final PortNumber portNumber;

        public BandwidthCheckTask(DeviceId deviceId, PortNumber portNumber) {
            this.deviceId = deviceId;
            this.portNumber = portNumber;
        }

        @Override
        public void run() {
            PortStatistics portStats = deviceService.getStatisticsForPort(this.deviceId, this.portNumber);
            if (portStats == null) {
                String str = String.format("No statistics for Device:%s, Port:%s", this.deviceId.toString(), this.portNumber.toString());
                log.info(str);
                return;
            }
            long bandwidth = (portStats.bytesReceived() + portStats.bytesSent()) * 8 / portStats.durationSec(); // Calculate bandwidth in bits per second
            if (bandwidth > BANDWIDTH_LIMIT) {
                // Bring the port down
                String str = String.format("DDoS detected at Device:%s, Port:%s", this.deviceId.toString(), this.portNumber.toString());
                log.info(str);
                deviceService.changePortState(this.deviceId, this.portNumber, false);
                str = String.format("Device:%s, Port:%s is temporarily down", this.deviceId.toString(), this.portNumber.toString());
                log.info(str);
                // Schedule port reactivation
                timer.schedule(new PortActivationTask(this.deviceId, this.portNumber), PORT_DOWN_TIME);
            }
        }
    }

    private class PortActivationTask extends TimerTask {

        private final DeviceId deviceId;
        private final PortNumber portNumber;

        public PortActivationTask(DeviceId deviceId, PortNumber portNumber) {
            this.deviceId = deviceId;
            this.portNumber = portNumber;
        }

        @Override
        public void run() {
            // Bring the port back up
            deviceService.changePortState(deviceId, portNumber, true);
            String str = String.format("Device:%s, Port:%s is back up", deviceId, portNumber);
            log.info(str);
        }
    }
}
