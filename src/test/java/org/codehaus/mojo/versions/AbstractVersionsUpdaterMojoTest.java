package org.codehaus.mojo.versions;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import junit.framework.TestCase;
import org.codehaus.mojo.versions.ordering.NumericVersionComparator;

/**
 * Basic tests for {@linkplain org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo}.
 *
 * @author Stephen Connolly
 */
public class AbstractVersionsUpdaterMojoTest
    extends TestCase
{

    private NumericVersionComparator instance = new NumericVersionComparator();

    /**
     * Basic test.
     *
     * @throws Exception when the test fails.
     */
    public void testBasic()
        throws Exception
    {
        assertEquals( 0, instance.compare( "1", "1" ) );
        assertTrue( instance.compare( "1", "2" ) < 0 );
        assertTrue( instance.compare( "2", "1" ) > 0 );
        assertTrue( instance.compare( "1", "1-SNAPSHOT" ) > 0 );
        assertTrue( instance.compare( "1", "1.0" ) > 0 );
        assertTrue( instance.compare( "1.1", "1" ) > 0 );
        assertTrue( instance.compare( "5.1.0.0.24", "5.1.0.0.9" ) > 0 );
        assertTrue( instance.compare( "5.1.0.0.2a4", "5.1.0.0.9" ) < 0 );
    }
}