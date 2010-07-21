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

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;

import javax.xml.stream.XMLStreamException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces any -SNAPSHOT versions with the corresponding release version (if it has been released).
 *
 * @author Stephen Connolly
 * @goal use-releases
 * @requiresProject true
 * @requiresDirectInvocation true
 * @since 1.0-alpha-3
 */
public class UseReleasesMojo
    extends AbstractVersionsDependencyUpdaterMojo
{

    // ------------------------------ FIELDS ------------------------------

    /**
     * Pattern to match a snapshot version.
     */
    public final Pattern matchSnapshotRegex = Pattern.compile( "^(.+)-((SNAPSHOT)|(\\d{8}\\.\\d{6}-\\d+))$" );

    /**
     * Whether to accept qualified release versions. This only affects
     * SNAPSHOT dependencies without specified qualifiers.
     *
     * @parameter expression="${acceptQualifiedReleases}" defaultValue="false"
     * @since 1.2
     */
    private Boolean acceptQualifiedReleases;

    /**
     * A comma separated list of version qualifier patterns to accept. Designed to allow specifying
     * the set of includes from the command line.
     * <p/>
     * <br/>When specifying includes from the pom, use the {@link #qualifierIncludes} configuration instead.
     * If this property is specified then the {@link #qualifierIncludes} configuration is ignored.
     *
     * @parameter expression="${qualifierIncludesList}"
     * @since 1.2
     */
    private String qualifierIncludesList = null;

    /**
     * A comma separated list of version qualifier patterns to discard. Designed to allow specifying
     * the set of excludes from the command line.
     * <p/>
     * <br/>When specifying excludes from the pom, use the {@link #qualifierExcludes} configuration instead.
     * If this property is specified then the {@link #qualifierExcludes} configuration is ignored.
     *
     * @parameter expression="${qualifierExcludesList}"
     * @since 1.2
     */
    private String qualifierExcludesList = null;

    /**
     * A list of version qualifier patterns to accept.
     * <p/>
     * <br/>This configuration setting is ignored if {@link #qualifierIncludesList} is
     * defined.
     *
     * @parameter
     * @since 1.2
     */
    private String[] qualifierIncludes = null;

    /**
     * A list of version qualifier patterns to discard.
     * <p/>
     * <br/>This configuration setting is ignored if {@link #qualifierExcludesList} is
     * defined.
     *
     * @parameter
     * @since 1.2
     */
    private String[] qualifierExcludes = null;

    /**
     * A list of compiled qualifier patterns to accept.
     */
    private Pattern[] compiledQualifierIncludes;

    /**
     * A list of compiled qualifier patterns to discard.
     */
    private Pattern[] compiledQualifierExcludes;

    // ------------------------------ METHODS --------------------------

    /**
     * @param pom the pom to update.
     * @throws org.apache.maven.plugin.MojoExecutionException
     *          when things go wrong
     * @throws org.apache.maven.plugin.MojoFailureException
     *          when things go wrong in a very bad way
     * @throws javax.xml.stream.XMLStreamException
     *          when things go wrong with XML streaming
     * @see org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo#update(org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader)
     */
    protected void update( ModifiedPomXMLEventReader pom )
        throws MojoExecutionException, MojoFailureException, XMLStreamException
    {
        try
        {
            if ( getProject().getDependencyManagement() != null && isProcessingDependencyManagement() )
            {
                useReleases( pom, getProject().getDependencyManagement().getDependencies() );
            }
            if ( isProcessingDependencies() )
            {
                useReleases( pom, getProject().getDependencies() );
            }
        }
        catch ( ArtifactMetadataRetrievalException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    private void useReleases( ModifiedPomXMLEventReader pom, Collection dependencies )
        throws XMLStreamException, MojoExecutionException, ArtifactMetadataRetrievalException
    {
        Iterator i = dependencies.iterator();

        while ( i.hasNext() )
        {
            Dependency dep = (Dependency) i.next();

            if ( isExcludeReactor() && isProducedByReactor( dep ) )
            {
                getLog().info( "Ignoring reactor dependency: " + toString( dep ) );
                continue;
            }

            String version = dep.getVersion();
            Matcher versionMatcher = matchSnapshotRegex.matcher( version );
            if ( versionMatcher.matches() )
            {
                Artifact artifact = this.toArtifact( dep );
                if ( !isIncluded( artifact ) )
                {
                    continue;
                }

                ArtifactVersion approachedVersion = new DefaultArtifactVersion( versionMatcher.group( 1 ) );
                String approachedVersionString = approachedVersion.toString();

                getLog().debug( "Looking for a release of " + toString( dep ) );
                ArtifactVersions versions = getHelper().lookupArtifactVersions( artifact, false );

                // perfect match
                VersionRange exactMatch = exactVersionRange( approachedVersionString );
                ArtifactVersion[] matches = versions.getVersions( exactMatch, false );
                if ( matches.length == 1 )
                {
                    setDependencyVersion( pom, dep, version, approachedVersionString );
                }
                // any qualified releases? alpha, beta?
                else if ( acceptQualifiedReleases == Boolean.TRUE &&
                    StringUtils.isEmpty( approachedVersion.getQualifier() ) && approachedVersion.getBuildNumber() == 0 )
                {
                    ArtifactVersion lower = decrement( approachedVersion );
                    ArtifactVersion upper = approachedVersion;
                    // range (1,2) will return all qualified v2-versions,
                    // hence 2-alpha is more than 1, but less than 2
                    ArtifactVersion[] proposedCandidates = versions.getVersions( lower, upper, false, false, false );
                    List/*<ArtifactVersions>*/ candidates = new ArrayList();
                    for ( int j = 0; j < proposedCandidates.length; j++ )
                    {
                        ArtifactVersion proposedCandidate = proposedCandidates[j];
                        if ( isIncludedQualifier( proposedCandidate.getQualifier() ) )
                        {
                            candidates.add( proposedCandidate );
                        }
                    }

                    if ( candidates.size() == 0 )
                    {
                        return;
                    }

                    Collections.sort( candidates, versions.getVersionComparator() );

                    ArtifactVersion last = (ArtifactVersion) candidates.get( candidates.size() - 1 );
                    setDependencyVersion( pom, dep, version, last.toString() );
                }
            }
        }
    }

    private VersionRange exactVersionRange( String approachedVersionString )
        throws MojoExecutionException
    {
        VersionRange exactMatch = null;
        try
        {
            exactMatch = VersionRange.createFromVersionSpec( "[" + approachedVersionString + "]" );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
        return exactMatch;
    }

    private ArtifactVersion decrement( ArtifactVersion approachedVersion )
    {
        int major = approachedVersion.getMajorVersion();
        int minor = approachedVersion.getMinorVersion();
        int incremental = approachedVersion.getIncrementalVersion();

        if ( incremental > 0 )
        {
            incremental--;
        }
        else if ( minor > 0 )
        {
            minor--;
        }
        else if ( major > 0 )
        {
            major--;
        }

        String version = String.valueOf( major ) + "." + String.valueOf( minor ) + "." + String.valueOf( incremental );
        return new DefaultArtifactVersion( version );
    }

    private void setDependencyVersion( ModifiedPomXMLEventReader pom, Dependency dep, String version,
                                       String releaseVersion )
        throws XMLStreamException
    {
        if ( PomHelper.setDependencyVersion( pom, dep.getGroupId(), dep.getArtifactId(), version, releaseVersion ) )
        {
            getLog().info( "Updated " + toString( dep ) + " to version " + releaseVersion );
        }
    }

    private boolean isIncludedQualifier( String qualifier )
    {
        Pattern[] excludes = getCompiledQualifierExcludes();
        Pattern[] includes = getCompiledQualifierIncludes();

        if ( excludes != null )
        {
            for ( int i = 0; i < excludes.length; i++ )
            {
                Pattern exclude = excludes[i];
                if ( exclude.matcher( qualifier ).matches() )
                {
                    return false;
                }
            }
        }
        if ( includes != null )
        {
            for ( int i = 0; i < includes.length; i++ )
            {
                Pattern include = includes[i];
                if ( !include.matcher( qualifier ).matches() )
                {
                    return false;
                }
            }
        }

        return true;
    }


    public Pattern[] getCompiledQualifierIncludes()
    {
        if (compiledQualifierIncludes != null)
            return compiledQualifierIncludes;

        if ( qualifierIncludesList != null )
        {
            qualifierIncludes = splitQualifiers( qualifierIncludesList );
            qualifierIncludesList = null;
        }

        if ( qualifierIncludes == null )
        {
            return null;
        }

        compiledQualifierIncludes = new Pattern[qualifierIncludes.length];
        for ( int i = 0; i < qualifierIncludes.length; i++ )
        {
            String qualifierInclude = qualifierIncludes[i];
            compiledQualifierIncludes[i] = Pattern.compile( "^" + qualifierInclude + "$" );
        }
        return compiledQualifierIncludes;
    }

    public Pattern[] getCompiledQualifierExcludes()
    {
        if (compiledQualifierExcludes != null)
            return compiledQualifierExcludes;

        if ( qualifierExcludesList != null )
        {
            qualifierExcludes = splitQualifiers( qualifierExcludesList );
            qualifierExcludesList = null;
        }

        if ( qualifierExcludes == null )
        {
            return null;
        }

        compiledQualifierExcludes = new Pattern[qualifierExcludes.length];
        for ( int i = 0; i < qualifierExcludes.length; i++ )
        {
            String qualifierExclude = qualifierExcludes[i];
            compiledQualifierExcludes[i] = Pattern.compile( "^" + qualifierExclude + "$" );
        }
        return compiledQualifierExcludes;
    }

    private String[] splitQualifiers( String commaSeparatedList )
    {
        return commaSeparatedList.replaceAll( "\\s", "" ).split( "," );
    }
}
