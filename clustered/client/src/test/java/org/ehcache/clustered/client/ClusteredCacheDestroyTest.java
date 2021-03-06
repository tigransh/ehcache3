/*
 * Copyright Terracotta, Inc.
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

package org.ehcache.clustered.client;

import org.ehcache.Cache;
import org.ehcache.CachePersistenceException;
import org.ehcache.PersistentCacheManager;
import org.ehcache.clustered.client.config.builders.ClusteredResourcePoolBuilder;
import org.ehcache.clustered.client.config.builders.ClusteredStoreConfigurationBuilder;
import org.ehcache.clustered.client.internal.UnitTestConnectionService;
import org.ehcache.clustered.common.Consistency;
import org.ehcache.clustered.common.internal.exceptions.ResourceBusyException;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;

import static org.ehcache.clustered.client.config.builders.ClusteringServiceConfigurationBuilder.cluster;
import static org.ehcache.config.builders.CacheConfigurationBuilder.newCacheConfigurationBuilder;
import static org.ehcache.config.builders.CacheManagerBuilder.newCacheManagerBuilder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class ClusteredCacheDestroyTest {

  private static final URI CLUSTER_URI = URI.create("terracotta://example.com:9540/my-application");

  private static final CacheManagerBuilder<PersistentCacheManager> clusteredCacheManagerBuilder =
      newCacheManagerBuilder()
          .with(cluster(CLUSTER_URI).autoCreate())
          .withCache("clustered-cache", newCacheConfigurationBuilder(Long.class, String.class,
              ResourcePoolsBuilder.newResourcePoolsBuilder()
                  .with(ClusteredResourcePoolBuilder.clusteredDedicated("primary-server-resource", 32, MemoryUnit.MB)))
              .add(ClusteredStoreConfigurationBuilder.withConsistency(Consistency.STRONG)));

  @Before
  public void definePassthroughServer() throws Exception {
    UnitTestConnectionService.add(CLUSTER_URI,
        new UnitTestConnectionService.PassthroughServerBuilder()
            .resource("primary-server-resource", 64, MemoryUnit.MB)
            .resource("secondary-server-resource", 64, MemoryUnit.MB)
            .build());
  }

  @After
  public void removePassthroughServer() throws Exception {
    UnitTestConnectionService.remove(CLUSTER_URI);
  }

  @Test
  public void testDestroyCacheWhenSingleClientIsConnected() throws CachePersistenceException {
    PersistentCacheManager persistentCacheManager = clusteredCacheManagerBuilder.build(true);

    persistentCacheManager.destroyCache("clustered-cache");

    final Cache<Long, String> cache = persistentCacheManager.getCache("clustered-cache", Long.class, String.class);

    assertThat(cache, nullValue());

  }

  @Test
  public void testDestroyFreesUpTheAllocatedResource() throws CachePersistenceException {

    PersistentCacheManager persistentCacheManager = clusteredCacheManagerBuilder.build(true);

    CacheConfigurationBuilder<Long, String> configBuilder = newCacheConfigurationBuilder(Long.class, String.class,
        ResourcePoolsBuilder.newResourcePoolsBuilder()
            .with(ClusteredResourcePoolBuilder.clusteredDedicated("primary-server-resource", 34, MemoryUnit.MB)));

    try {
      Cache<Long, String> anotherCache = persistentCacheManager.createCache("another-cache", configBuilder);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage(), is("Cache 'another-cache' creation in EhcacheManager failed."));
    }

    persistentCacheManager.destroyCache("clustered-cache");

    Cache<Long, String> anotherCache = persistentCacheManager.createCache("another-cache", configBuilder);

    anotherCache.put(1L, "One");
    assertThat(anotherCache.get(1L), is("One"));
  }

  @Test
  public void testDestroyCacheWhenMultipleClientsConnected() {
    PersistentCacheManager persistentCacheManager1 = clusteredCacheManagerBuilder.build(true);
    PersistentCacheManager persistentCacheManager2 = clusteredCacheManagerBuilder.build(true);

    final Cache<Long, String> cache1 = persistentCacheManager1.getCache("clustered-cache", Long.class, String.class);

    final Cache<Long, String> cache2 = persistentCacheManager2.getCache("clustered-cache", Long.class, String.class);

    try {
      persistentCacheManager1.destroyCache("clustered-cache");
      fail();
    } catch (CachePersistenceException e) {
      assertThat(e.getMessage(), containsString("Cannot destroy clustered tier"));
      assertThat(getRootCause(e), instanceOf(ResourceBusyException.class));
    }

    try {
      cache1.put(1L, "One");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage(), is("State is UNINITIALIZED"));
    }

    assertThat(cache2.get(1L), nullValue());

    cache2.put(1L, "One");

    assertThat(cache2.get(1L), is("One"));
  }

  private static Throwable getRootCause(Throwable t) {
    if (t.getCause() == null || t.getCause() == t) {
      return t;
    }
    return getRootCause(t.getCause());
  }

}

