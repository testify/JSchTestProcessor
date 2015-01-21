/*
 * Copyright 2015 Codice Foundation
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.codice.testify.processors.JSch;

import com.jcraft.jsch.*;
import org.codice.testify.objects.*;
import org.codice.testify.objects.Request;
import org.codice.testify.processors.TestProcessor;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import java.io.IOException;
import java.io.InputStream;

/**
 * The JSchTestProcessor class is a Testify TestProcessor service for SSH command processing
 */
public class JSchTestProcessor implements BundleActivator, TestProcessor {

    @Override
    public Response executeTest(Request request) {

        //Define variables
        String username;
        String pass;
        String connectIP;
        int port;
        StringBuilder commandResponse = new StringBuilder();

        //Split endpoint into components (assuming endpoint is given in the format USERNAME:PASSWORD@IP:PORT)
        String[] endpointComponents = request.getEndpoint().split(":");
        if (endpointComponents.length == 3) {

            //Set username and port
            username = endpointComponents[0];
            port = Integer.parseInt(endpointComponents[2]);

            //Split password and IP
            String[] passIP = endpointComponents[1].split("@");
            if (passIP.length != 2) {
                TestifyLogger.error("PASSWORD and IP in endpoint must be separated by @", this.getClass().getSimpleName());
                return new Response(null);
            } else {
                pass = passIP[0];
                connectIP = passIP[1];
            }
        } else {
            TestifyLogger.error("JSch endpoint must be in this format: USERNAME:PASSWORD@IP:PORT", this.getClass().getSimpleName());
            return new Response(null);
        }

        //Connect session
        JSch jschSSH = new JSch();
        Session sessionConnection;
        try {
            sessionConnection = jschSSH.getSession(username, connectIP, port);
            sessionConnection.setPassword(pass);
            sessionConnection.setConfig("StrictHostKeyChecking", "no");
            sessionConnection.connect();
        } catch (JSchException e) {
            TestifyLogger.error(e.getMessage(), this.getClass().getSimpleName());
            return new Response(null);
        }


        //Separate commands and send
        String command = request.getTestBlock().replaceAll(System.lineSeparator(), ";");
        TestifyLogger.debug("SSH Command: " + command, this.getClass().getSimpleName());
        Channel channel = null;
        try {
            channel = sessionConnection.openChannel("exec");
            ((ChannelExec)channel).setCommand(command);
            channel.connect();
        } catch (JSchException e) {
            TestifyLogger.error(e.getMessage(), this.getClass().getSimpleName());
        }

        //Check if channel was set
        if (channel != null) {

            //Check if timeout property is set (prop name jschtestprocessor.response.timeout)
            int timeout = 30000; //milliseconds
            TestProperties testProperties = (TestProperties)AllObjects.getObject("testProperties");
            if (testProperties.propertyExists("jschtestprocessor.response.timeout")) {
                timeout = Integer.parseInt(testProperties.getFirstValue("jschtestprocessor.response.timeout"));
                TestifyLogger.debug("Timeout set to " + timeout + " milliseconds", this.getClass().getSimpleName());
            } else {
                TestifyLogger.debug("Timeout not set in properties file. Defaulting  to " + timeout + " milliseconds", this.getClass().getSimpleName());
                TestifyLogger.debug("To set timeout, add property jschtestprocessor.response.timeout", this.getClass().getSimpleName());
            }

            //Receive response from system
            try {
                InputStream commandOut = channel.getInputStream();

                //Wait for response until timeout
                int waitTime = 0;
                while (commandOut.available() == 0 && waitTime < timeout) {
                    Thread.sleep(1000);
                    waitTime = waitTime + 1000;
                }
                TestifyLogger.debug("Waited " + waitTime + " milliseconds for response", this.getClass().getSimpleName());

                int nextByte = commandOut.read();
                while (nextByte != 0xffffffff) {
                    commandResponse.append((char) nextByte);
                    nextByte = commandOut.read();
                }
            } catch (IOException | InterruptedException e) {
                TestifyLogger.error(e.getMessage(), this.getClass().getSimpleName());
            }

            //Close SSH session
            channel.disconnect();
            sessionConnection.disconnect();

        } else {
            TestifyLogger.error("JSch channel not set", this.getClass().getSimpleName());
            return new Response(null);
        }

        return new Response(commandResponse.toString());
    }

    @Override
    public void start(BundleContext bundleContext) throws Exception {

        //Register the JSch TestProcessor service
        bundleContext.registerService(TestProcessor.class.getName(), new JSchTestProcessor(), null);

    }

    @Override
    public void stop(BundleContext bundleContext) throws Exception {

    }
}