/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.bolt;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.apache.storm.task.OutputCollector;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.TestUtil;
import com.digitalpebble.stormcrawler.bolt.JSoupParserBolt;
import com.digitalpebble.stormcrawler.parse.filter.ParsingTester;
import com.digitalpebble.stormcrawler.util.RobotsTags;

public class JSoupParserBoltTest extends ParsingTester {

    /*
     * 
     * some sample tags:
     * 
     * <meta name="robots" content="index,follow"> <meta name="robots"
     * content="noindex,follow"> <meta name="robots" content="index,nofollow">
     * <meta name="robots" content="noindex,nofollow">
     * 
     * <META HTTP-EQUIV="Pragma" CONTENT="no-cache">
     */

    public static String[] tests = {
            "<html><head><title>test page</title>"
                    + "<META NAME=\"ROBOTS\" CONTENT=\"NONE\"> "
                    + "</head><body>" + " some text" + "</body></html>",

            "<html><head><title>test page</title>"
                    + "<meta name=\"robots\" content=\"all\"> "
                    + "</head><body>" + " some text" + "</body></html>",

            "<html><head><title>test page</title>"
                    + "<MeTa NaMe=\"RoBoTs\" CoNtEnT=\"nOnE\"> "
                    + "</head><body>" + " some text" + "</body></html>",

            "<html><head><title>test page</title>"
                    + "<meta name=\"robots\" content=\"none\"> "
                    + "</head><body>" + " some text" + "</body></html>",

            "<html><head><title>test page</title>"
                    + "<meta name=\"robots\" content=\"noindex,nofollow\"> "
                    + "</head><body>" + " some text" + "</body></html>",

            "<html><head><title>test page</title>"
                    + "<meta name=\"robots\" content=\"noindex,follow\"> "
                    + "</head><body>" + " some text" + "</body></html>",

            "<html><head><title>test page</title>"
                    + "<meta name=\"robots\" content=\"index,nofollow\"> "
                    + "</head><body>" + " some text" + "</body></html>",

            "<html><head><title>test page</title>"
                    + "<meta name=\"robots\" content=\"index,follow\"> "
                    + "<base href=\"http://www.nutch.org/\">" + "</head><body>"
                    + " some text" + "</body></html>",

            "<html><head><title>test page</title>" + "<meta name=\"robots\"> "
                    + "<base href=\"http://www.nutch.org/base/\">"
                    + "</head><body>" + " some text" + "</body></html>",

    };

    public static final boolean[][] answers = { { true, true, true }, // NONE
            { false, false, false }, // all
            { true, true, true }, // nOnE
            { true, true, true }, // none
            { true, true, false }, // noindex,nofollow
            { true, false, false }, // noindex,follow
            { false, true, false }, // index,nofollow
            { false, false, false }, // index,follow
            { false, false, false }, // missing!
    };

    @Before
    public void setupParserBolt() {
        bolt = new JSoupParserBolt();
        setupParserBolt(bolt);
    }

    @Test
    /**
     * Checks that content in script is not included in the text representation
     **/
    public void testNoScriptInText() throws IOException {

        bolt.prepare(new HashMap(), TestUtil.getMockedTopologyContext(),
                new OutputCollector(output));

        parse("http://www.digitalpebble.com", "digitalpebble.com.html");

        List<Object> parsedTuple = output.getEmitted().remove(0);

        // check in the metadata that the values match
        String text = (String) parsedTuple.get(3);

        Assert.assertFalse(
                "Text should not contain the content of script tags",
                text.contains("urchinTracker"));
    }

    @Test
    /**
     * Checks that individual links marked as rel="nofollow" are not followed
     **/
    public void testNoFollowOutlinks() throws IOException {

        bolt.prepare(new HashMap(), TestUtil.getMockedTopologyContext(),
                new OutputCollector(output));

        parse("http://www.digitalpebble.com", "digitalpebble.com.html");

        List<List<Object>> statusTuples = output
                .getEmitted(Constants.StatusStreamName);

        Assert.assertEquals(10, statusTuples.size());
    }

    @Test
    public void testHTTPRobots() throws IOException {

        bolt.prepare(new HashMap(), TestUtil.getMockedTopologyContext(),
                new OutputCollector(output));

        Metadata metadata = new Metadata();
        metadata.setValues("X-Robots-Tag",
                new String[] { "noindex", "nofollow" });

        parse("http://www.digitalpebble.com", "digitalpebble.com.html",
                metadata);

        List<List<Object>> statusTuples = output
                .getEmitted(Constants.StatusStreamName);

        // no outlinks at all
        Assert.assertEquals(0, statusTuples.size());

        Assert.assertEquals(1, output.getEmitted().size());
        List<Object> parsedTuple = output.getEmitted().remove(0);

        // check in the metadata that the values match
        metadata = (Metadata) parsedTuple.get(2);
        Assert.assertNotNull(metadata);

        boolean isNoIndex = Boolean.parseBoolean(metadata
                .getFirstValue(RobotsTags.ROBOTS_NO_INDEX));
        boolean isNoFollow = Boolean.parseBoolean(metadata
                .getFirstValue(RobotsTags.ROBOTS_NO_FOLLOW));
        boolean isNoCache = Boolean.parseBoolean(metadata
                .getFirstValue(RobotsTags.ROBOTS_NO_CACHE));

        Assert.assertEquals("incorrect noIndex", true, isNoIndex);
        Assert.assertEquals("incorrect noFollow", true, isNoFollow);
        Assert.assertEquals("incorrect noCache", false, isNoCache);
    }

    @Test
    public void testRobotsMetaProcessor() throws IOException {

        bolt.prepare(new HashMap(), TestUtil.getMockedTopologyContext(),
                new OutputCollector(output));

        for (int i = 0; i < tests.length; i++) {

            byte[] bytes = tests[i].getBytes();

            parse("http://www.digitalpebble.com", bytes, new Metadata());

            Assert.assertEquals(1, output.getEmitted().size());
            List<Object> parsedTuple = output.getEmitted().remove(0);

            // check in the metadata that the values match
            Metadata metadata = (Metadata) parsedTuple.get(2);
            Assert.assertNotNull(metadata);

            boolean isNoIndex = Boolean.parseBoolean(metadata
                    .getFirstValue(RobotsTags.ROBOTS_NO_INDEX));
            boolean isNoFollow = Boolean.parseBoolean(metadata
                    .getFirstValue(RobotsTags.ROBOTS_NO_FOLLOW));
            boolean isNoCache = Boolean.parseBoolean(metadata
                    .getFirstValue(RobotsTags.ROBOTS_NO_CACHE));

            Assert.assertEquals("incorrect noIndex value on doc " + i,
                    answers[i][0], isNoIndex);
            Assert.assertEquals("incorrect noFollow value on doc " + i,
                    answers[i][1], isNoFollow);
            Assert.assertEquals("incorrect noCache value on doc " + i,
                    answers[i][2], isNoCache);

        }
    }

}
