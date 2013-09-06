package com.yammer.breakerbox.service.resources;

import com.google.common.base.Optional;
import com.google.common.collect.*;
import com.yammer.breakerbox.service.azure.DependencyEntity;
import com.yammer.breakerbox.service.azure.ServiceEntity;
import com.yammer.breakerbox.service.comparable.DescendingRowOrder;
import com.yammer.breakerbox.service.core.BreakerboxStore;
import com.yammer.breakerbox.service.core.DependencyId;
import com.yammer.breakerbox.service.core.Instances;
import com.yammer.breakerbox.service.core.ServiceId;
import com.yammer.breakerbox.service.store.TenacityPropertyKeysStore;
import com.yammer.breakerbox.service.views.ConfigureView;
import com.yammer.breakerbox.service.views.NoPropertyKeysView;
import com.yammer.dropwizard.auth.Auth;
import com.yammer.dropwizard.auth.basic.BasicCredentials;
import com.yammer.dropwizard.views.View;
import com.yammer.metrics.annotation.Timed;
import com.yammer.tenacity.core.config.CircuitBreakerConfiguration;
import com.yammer.tenacity.core.config.TenacityConfiguration;
import com.yammer.tenacity.core.config.ThreadPoolConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Comparator;

@Path("/configure/{service}")
public class ConfigureResource {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigureResource.class);

    private final BreakerboxStore breakerboxStore;
    private final TenacityPropertyKeysStore tenacityPropertyKeysStore;

    public ConfigureResource(BreakerboxStore breakerboxStore, TenacityPropertyKeysStore tenacityPropertyKeysStore) {
        this.breakerboxStore = breakerboxStore;
        this.tenacityPropertyKeysStore = tenacityPropertyKeysStore;
    }

    @GET @Timed @Produces(MediaType.TEXT_HTML)
    public View render(@Auth BasicCredentials creds, @PathParam("service") String serviceName) {
        final ServiceId serviceId = ServiceId.from(serviceName);
        final Optional<String> firstDependencyKey = FluentIterable
                .from(tenacityPropertyKeysStore.tenacityPropertyKeysFor(Instances.propertyKeyUris(serviceId)))
                .first();
        if (firstDependencyKey.isPresent()) {
            return create(serviceId, DependencyId.from(firstDependencyKey.get()));
        } else {
            return new NoPropertyKeysView(serviceId);
        }
    }

    @GET @Timed @Produces(MediaType.TEXT_HTML)
    @Path("/{dependency}")
    public ConfigureView render(@Auth BasicCredentials creds,
                                @PathParam("service") String serviceName,
                                @PathParam("dependency") String dependencyName) {
        return create(ServiceId.from(serviceName), DependencyId.from(dependencyName));
    }

    private ConfigureView create(ServiceId serviceId,
                                 DependencyId dependencyId) {
        final Optional<ServiceEntity> serviceEntity = breakerboxStore.retrieve(serviceId, dependencyId);
        final ImmutableList<DependencyEntity> dependencyEntities = breakerboxStore.listDependencyConfigurations(dependencyId);
        final ImmutableSet<String> propertyKeys = tenacityPropertyKeysStore.tenacityPropertyKeysFor(Instances.propertyKeyUris(serviceId));
        return new ConfigureView(
                serviceId,
                Ordering.from(new SortKeyFirst(dependencyId))
                        .immutableSortedCopy(propertyKeys),
                serviceEntity
                        .or(ServiceEntity.build(serviceId, dependencyId)).getTenacityConfiguration()
                        .or(new TenacityConfiguration()),
                Ordering.from(new DescendingRowOrder<DependencyEntity>())
                        .immutableSortedCopy(dependencyEntities));
    }

    private static class SortKeyFirst implements Comparator<String> {
        private final String sortFirst;

        private SortKeyFirst(DependencyId sortFirst) {
            this.sortFirst = sortFirst.getId();
        }

        @Override
        public int compare(String o1, String o2) {
            return ComparisonChain
                    .start()
                    .compareTrueFirst(o1.equals(sortFirst), o2.equals(sortFirst))
                    .result();
        }
    }


    @GET @Timed @Produces(MediaType.APPLICATION_JSON)
    @Path("/{dependency}")
    public TenacityConfiguration get(@PathParam("service") String serviceName,
                                     @PathParam("dependency") String dependencyName) {
        final Optional<ServiceEntity> serviceEntity = breakerboxStore.retrieve(
                ServiceId.from(serviceName),
                DependencyId.from(dependencyName));
        if (serviceEntity.isPresent()) {
            final Optional<TenacityConfiguration> tenacityConfiguration = serviceEntity.get().getTenacityConfiguration();
            if (tenacityConfiguration.isPresent()) {
                return tenacityConfiguration.get();
            }
        }
        throw new WebApplicationException();
    }


    @POST @Timed @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response configure(@Auth BasicCredentials creds,
                              @PathParam("service") String serviceName,
                              @FormParam("dependency") String dependencyName,
                              @FormParam("executionTimeout") Integer executionTimeout,
                              @FormParam("requestVolumeThreshold") Integer requestVolumeThreshold,
                              @FormParam("errorThresholdPercentage") Integer errorThresholdPercentage,
                              @FormParam("sleepWindow") Integer sleepWindow,
                              @FormParam("circuitBreakerstatisticalWindow") Integer circuitBreakerstatisticalWindow,
                              @FormParam("circuitBreakerStatisticalWindowBuckets") Integer circuitBreakerStatisticalWindowBuckets,
                              @FormParam("threadPoolCoreSize") Integer threadPoolCoreSize,
                              @FormParam("keepAliveMinutes") Integer keepAliveMinutes,
                              @FormParam("maxQueueSize") Integer maxQueueSize,
                              @FormParam("queueSizeRejectionThreshold") Integer queueSizeRejectionThreshold,
                              @FormParam("threadpoolStatisticalWindow") Integer threadpoolStatisticalWindow,
                              @FormParam("threadpoolStatisticalWindowBuckets") Integer threadpoolStatisticalWindowBuckets) {
        final String username = creds.getUsername();
        final TenacityConfiguration tenacityConfiguration = new TenacityConfiguration(
                new ThreadPoolConfiguration(
                        threadPoolCoreSize,
                        keepAliveMinutes,
                        maxQueueSize,
                        queueSizeRejectionThreshold,
                        threadpoolStatisticalWindow,
                        threadpoolStatisticalWindowBuckets),
                new CircuitBreakerConfiguration(
                        requestVolumeThreshold,
                        sleepWindow,
                        errorThresholdPercentage,
                        circuitBreakerstatisticalWindow,
                        circuitBreakerStatisticalWindowBuckets),
                executionTimeout);
        if (commitSuccessful(serviceName, dependencyName, tenacityConfiguration, username)) {
            return Response
                    .created(URI.create(String.format("/configuration/%s/%s", serviceName, dependencyName)))
                    .build();
        } else {
            return Response.serverError().build();
        }
    }

    private boolean commitSuccessful(String serviceName, String dependencyName, TenacityConfiguration tenacityConfiguration, String username) {
        if(username == null || "".equals(username)){
            username = "unknown_user";
            LOG.warn("Unable to resolve username from credentials while submitting configuration");
        }

        //double dispatch for now
        return breakerboxStore.storeDependencyEntity(
                DependencyId.from(dependencyName),
                System.currentTimeMillis(),
                tenacityConfiguration,
                username)

                &&

                breakerboxStore.storeServiceEntity(ServiceEntity.build(
                ServiceId.from(serviceName),
                DependencyId.from(dependencyName),
                tenacityConfiguration));
    }
}
