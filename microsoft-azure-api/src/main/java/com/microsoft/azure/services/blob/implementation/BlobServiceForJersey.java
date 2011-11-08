package com.microsoft.azure.services.blob.implementation;

import java.io.InputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Inject;
import javax.inject.Named;

import com.microsoft.azure.ServiceException;
import com.microsoft.azure.http.ServiceFilter;
import com.microsoft.azure.services.blob.AccessCondition;
import com.microsoft.azure.services.blob.AccessConditionHeaderType;
import com.microsoft.azure.services.blob.AcquireLeaseOptions;
import com.microsoft.azure.services.blob.Blob;
import com.microsoft.azure.services.blob.BlobConfiguration;
import com.microsoft.azure.services.blob.BlobListingDetails;
import com.microsoft.azure.services.blob.BlobProperties;
import com.microsoft.azure.services.blob.BlobService;
import com.microsoft.azure.services.blob.BlobSharedKeyLiteFilter;
import com.microsoft.azure.services.blob.BlobSnapshot;
import com.microsoft.azure.services.blob.BlockList;
import com.microsoft.azure.services.blob.CommitBlobBlocksOptions;
import com.microsoft.azure.services.blob.ContainerACL;
import com.microsoft.azure.services.blob.ContainerListingDetails;
import com.microsoft.azure.services.blob.ContainerProperties;
import com.microsoft.azure.services.blob.CopyBlobOptions;
import com.microsoft.azure.services.blob.CreateBlobBlockOptions;
import com.microsoft.azure.services.blob.CreateBlobOptions;
import com.microsoft.azure.services.blob.CreateBlobPagesOptions;
import com.microsoft.azure.services.blob.CreateBlobPagesResult;
import com.microsoft.azure.services.blob.CreateBlobSnapshotOptions;
import com.microsoft.azure.services.blob.CreateContainerOptions;
import com.microsoft.azure.services.blob.DeleteBlobOptions;
import com.microsoft.azure.services.blob.DeleteContainerOptions;
import com.microsoft.azure.services.blob.GetBlobMetadataOptions;
import com.microsoft.azure.services.blob.GetBlobMetadataResult;
import com.microsoft.azure.services.blob.GetBlobOptions;
import com.microsoft.azure.services.blob.GetBlobPropertiesOptions;
import com.microsoft.azure.services.blob.ListBlobBlocksOptions;
import com.microsoft.azure.services.blob.ListBlobBlocksResult;
import com.microsoft.azure.services.blob.ListBlobRegionsOptions;
import com.microsoft.azure.services.blob.ListBlobRegionsResult;
import com.microsoft.azure.services.blob.ListBlobsOptions;
import com.microsoft.azure.services.blob.ListBlobsResult;
import com.microsoft.azure.services.blob.ListContainersOptions;
import com.microsoft.azure.services.blob.ListContainersResult;
import com.microsoft.azure.services.blob.ServiceProperties;
import com.microsoft.azure.services.blob.SetBlobMetadataOptions;
import com.microsoft.azure.services.blob.SetBlobMetadataResult;
import com.microsoft.azure.services.blob.SetBlobPropertiesOptions;
import com.microsoft.azure.services.blob.SetBlobPropertiesResult;
import com.microsoft.azure.services.blob.SetContainerMetadataOptions;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.WebResource.Builder;
import com.sun.jersey.core.util.Base64;

public class BlobServiceForJersey implements BlobService {
    // private static Log log = LogFactory.getLog(BlobServiceForJersey.class);

    // TODO: Should we make this a configuration options?
    private static final String API_VERSION = "2011-08-18";
    private final Client channel;
    private final String accountName;
    private final String url;
    private final Integer timeout;
    private final RFC1123DateConverter dateMapper;
    private final ServiceFilter[] filters;
    private final BlobSharedKeyLiteFilter filter;

    /*
     * TODO: How can we make "timeout" optional? TODO: How to make "filter"
     * configurable though code?
     */
    @Inject
    public BlobServiceForJersey(Client channel, @Named(BlobConfiguration.ACCOUNT_NAME) String accountName, @Named(BlobConfiguration.URL) String url,
            @Named(BlobConfiguration.TIMEOUT) String timeout, BlobSharedKeyLiteFilter filter) {

        this.channel = channel;
        this.accountName = accountName;
        this.url = url;
        this.filter = filter;
        this.timeout = (timeout == null ? null : Integer.parseInt(timeout));
        this.dateMapper = new RFC1123DateConverter();
        this.filters = new ServiceFilter[0];
        channel.addFilter(filter);
    }

    public BlobServiceForJersey(Client channel, ServiceFilter[] filters, String accountName,
            String url, Integer timeout, BlobSharedKeyLiteFilter filter, RFC1123DateConverter dateMapper) {
        this.channel = channel;
        this.filters = filters;
        this.accountName = accountName;
        this.url = url;
        this.filter = filter;
        this.dateMapper = dateMapper;
        this.timeout = timeout;
    }

    public BlobService withFilter(ServiceFilter filter) {
        ServiceFilter[] newFilters = Arrays.copyOf(filters, filters.length + 1);
        newFilters[filters.length] = filter;
        return new BlobServiceForJersey(this.channel, newFilters, this.accountName, this.url, this.timeout, this.filter, this.dateMapper);
    }

    private void ThrowIfError(ClientResponse r) {
        if (r.getStatus() >= 300) {
            throw new UniformInterfaceException(r);
        }
    }

    private class EnumCommaStringBuilder<E extends Enum<E>> {
        private final StringBuilder sb = new StringBuilder();

        public void addValue(EnumSet<E> enumSet, E value, String representation) {
            if (enumSet.contains(value)) {
                if (sb.length() >= 0) {
                    sb.append(",");
                }
                sb.append(representation);
            }
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

    private WebResource addOptionalQueryParam(WebResource webResource, String key, Object value) {
        if (value != null) {
            webResource = webResource.queryParam(key, value.toString());
        }
        return webResource;
    }

    private WebResource addOptionalQueryParam(WebResource webResource, String key, int value, int defaultValue) {
        if (value != defaultValue) {
            webResource = webResource.queryParam(key, Integer.toString(value));
        }
        return webResource;
    }

    private Builder addOptionalHeader(Builder builder, String name, Object value) {
        if (value != null) {
            builder = builder.header(name, value);
        }
        return builder;
    }

    private Builder addOptionalMetadataHeader(Builder builder, Map<String, String> metadata) {
        for (Entry<String, String> entry : metadata.entrySet()) {
            builder = builder.header("x-ms-meta-" + entry.getKey(), entry.getValue());
        }
        return builder;
    }

    private Builder addOptionalRangeHeader(Builder builder, Long rangeStart, Long rangeEnd) {
        if (rangeStart != null) {
            String range = rangeStart.toString() + "-";
            if (rangeEnd != null) {
                range += rangeEnd.toString();
            }
            builder = addOptionalHeader(builder, "Range", "bytes=" + range);
        }
        return builder;
    }

    private Builder addOptionalAccessContitionHeader(Builder builder, AccessCondition accessCondition) {
        if (accessCondition != null) {
            if (accessCondition.getHeader() != AccessConditionHeaderType.NONE) {
                builder = addOptionalHeader(builder, accessCondition.getHeader().toString(), accessCondition.getValue());
            }
        }
        return builder;
    }

    private Builder addOptionalSourceAccessContitionHeader(Builder builder, AccessCondition accessCondition) {
        if (accessCondition != null) {
            if (accessCondition.getHeader() != AccessConditionHeaderType.NONE) {
                String headerName;
                switch (accessCondition.getHeader()) {
                case IF_MATCH:
                    headerName = "x-ms-source-if-match";
                case IF_UNMODIFIED_SINCE:
                    headerName = "x-ms-source-if-unmodified-since";
                case IF_MODIFIED_SINCE:
                    headerName = "x-ms-source-if-modified-since";
                case IF_NONE_MATCH:
                    headerName = "x-ms-source-if-none-match";
                default:
                    headerName = "";
                }
                builder = addOptionalHeader(builder, headerName, accessCondition.getValue());
            }
        }
        return builder;
    }

    private Builder addPutBlobHeaders(CreateBlobOptions options, Builder builder) {
        builder = addOptionalHeader(builder, "Content-Type", options.getContentType());
        if (options.getContentType() == null) {
            // Note: Add content type here to enable proper HMAC signing
            builder = builder.type("application/octet-stream");
        }
        builder = addOptionalHeader(builder, "Content-Encoding", options.getContentEncoding());
        builder = addOptionalHeader(builder, "Content-Language", options.getContentLanguage());
        builder = addOptionalHeader(builder, "Content-MD5", options.getContentMD5());
        builder = addOptionalHeader(builder, "Cache-Control", options.getCacheControl());
        builder = addOptionalHeader(builder, "x-ms-blob-content-type", options.getBlobContentType());
        builder = addOptionalHeader(builder, "x-ms-blob-content-encoding", options.getBlobContentEncoding());
        builder = addOptionalHeader(builder, "x-ms-blob-content-language", options.getBlobContentLanguage());
        builder = addOptionalHeader(builder, "x-ms-blob-content-md5", options.getBlobContentMD5());
        builder = addOptionalHeader(builder, "x-ms-blob-cache-control", options.getBlobCacheControl());
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalMetadataHeader(builder, options.getMetadata());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        return builder;
    }

    private BlobProperties getBlobPropertiesFromResponse(ClientResponse response) {
        BlobProperties properties = new BlobProperties();

        // Last-Modified
        properties.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));

        HashMap<String, String> metadata = getMetadataFromHeaders(response);
        properties.setMetadata(metadata);

        //
        properties.setBlobType(response.getHeaders().getFirst("x-ms-blob-type"));
        properties.setLeaseStatus(response.getHeaders().getFirst("x-ms-lease-status"));

        properties.setContentLength(Long.parseLong(response.getHeaders().getFirst("Content-Length")));
        properties.setContentType(response.getHeaders().getFirst("Content-Type"));
        properties.setContentMD5(response.getHeaders().getFirst("Content-MD5"));
        properties.setContentEncoding(response.getHeaders().getFirst("Content-Encoding"));
        properties.setContentLanguage(response.getHeaders().getFirst("Content-Language"));
        properties.setCacheControl(response.getHeaders().getFirst("Cache-Control"));

        properties.setEtag(response.getHeaders().getFirst("Etag"));
        if (response.getHeaders().containsKey("x-ms-blob-sequence-number")) {
            properties.setSequenceNUmber(Long.parseLong(response.getHeaders().getFirst("x-ms-blob-sequence-number")));
        }
        return properties;
    }

    private HashMap<String, String> getMetadataFromHeaders(ClientResponse response) {
        HashMap<String, String> metadata = new HashMap<String, String>();
        for (Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
            if (entry.getKey().startsWith("x-ms-meta-")) {
                String name = entry.getKey().substring("x-ms-meta-".length());
                String value = entry.getValue().get(0);
                metadata.put(name, value);
            }
        }
        return metadata;
    }

    private WebResource getResource() {
        WebResource webResource = channel.resource(url).path("/");
        webResource = addOptionalQueryParam(webResource, "timeout", this.timeout);

        return webResource;
    }

    private WebResource setCanonicalizedResource(WebResource webResource, String resourceName, String operation) {
        String value = "/" + this.accountName + "/";
        if (resourceName != null) {
            value += resourceName;
        }

        if (operation != null) {
            value += "?comp=" + operation;
        }

        // TODO: Is this the best way to pass a parameter to a filter?
        webResource.setProperty("canonicalizedResource", value);
        return webResource;
    }

    private String getCopyBlobSourceName(String sourceContainer, String sourceBlob, CopyBlobOptions options) {
        // Specifies the name of the source blob, in one of the following
        // formats:
        // Blob in named container: /accountName/containerName/blobName
        //
        // Snapshot in named container:
        // /accountName/containerName/blobName?snapshot=<DateTime>
        //
        // Blob in root container: /accountName/blobName
        //
        // Snapshot in root container: /accountName/blobName?snapshot=<DateTime>
        String sourceName = "/" + this.accountName;
        if (sourceContainer != null) {
            sourceName += "/" + sourceContainer;
        }
        sourceName += "/" + sourceBlob;
        if (options.getSourceSnapshot() != null) {
            sourceName += "?snapshot=" + options.getSourceSnapshot();
        }
        return sourceName;
    }

    public ServiceProperties getServiceProperties() throws ServiceException {
        WebResource webResource = getResource().path("/").queryParam("resType", "service").queryParam("comp", "properties");
        webResource = setCanonicalizedResource(webResource, null, "properties");

        WebResource.Builder builder = webResource.header("x-ms-version", API_VERSION);

        return builder.get(ServiceProperties.class);
    }

    public void setServiceProperties(ServiceProperties serviceProperties) throws ServiceException {
        WebResource webResource = getResource().path("/").queryParam("resType", "service").queryParam("comp", "properties");
        webResource = setCanonicalizedResource(webResource, null, "properties");

        WebResource.Builder builder = webResource.header("x-ms-version", API_VERSION);

        // Note: Add content type here to enable proper HMAC signing
        builder.type("application/xml").put(serviceProperties);
    }

    public void createContainer(String container) throws ServiceException {
        createContainer(container, new CreateContainerOptions());
    }

    public void createContainer(String container, CreateContainerOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).queryParam("resType", "container");
        webResource = setCanonicalizedResource(webResource, container, null);

        WebResource.Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalMetadataHeader(builder, options.getMetadata());
        builder = addOptionalHeader(builder, "x-ms-blob-public-access", options.getPublicAccess());

        // Note: Add content type here to enable proper HMAC signing
        builder.type("text/plain").put("");
    }

    public void deleteContainer(String container) throws ServiceException {
        deleteContainer(container, new DeleteContainerOptions());
    }

    public void deleteContainer(String container, DeleteContainerOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).queryParam("resType", "container");
        webResource = setCanonicalizedResource(webResource, container, null);

        WebResource.Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        builder.delete();
    }

    public ContainerProperties getContainerProperties(String container) throws ServiceException {
        return getContainerPropertiesImpl(container, null);
    }

    public ContainerProperties getContainerMetadata(String container) throws ServiceException {
        return getContainerPropertiesImpl(container, "metadata");
    }

    private ContainerProperties getContainerPropertiesImpl(String container, String operation) throws ServiceException {
        WebResource webResource = getResource().path(container).queryParam("resType", "container");
        webResource = addOptionalQueryParam(webResource, "comp", operation);
        webResource = setCanonicalizedResource(webResource, container, operation);

        Builder builder = webResource.header("x-ms-version", API_VERSION);

        ClientResponse response = builder.get(ClientResponse.class);
        ThrowIfError(response);

        ContainerProperties properties = new ContainerProperties();
        properties.setEtag(response.getHeaders().getFirst("ETag"));
        properties.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));
        properties.setMetadata(getMetadataFromHeaders(response));

        return properties;
    }

    public ContainerACL getContainerACL(String container) throws ServiceException {
        WebResource webResource = getResource().path(container).queryParam("resType", "container").queryParam("comp", "acl");
        webResource = setCanonicalizedResource(webResource, container, "acl");

        Builder builder = webResource.header("x-ms-version", API_VERSION);

        ClientResponse response = builder.get(ClientResponse.class);
        ThrowIfError(response);

        ContainerACL.SignedIdentifiers si = response.getEntity(ContainerACL.SignedIdentifiers.class);
        ContainerACL acl = new ContainerACL();
        acl.setSignedIdentifiers(si.getSignedIdentifiers());
        acl.setPublicAccess(response.getHeaders().getFirst("x-ms-blob-public-access"));
        acl.setEtag(response.getHeaders().getFirst("ETag"));
        acl.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));
        return acl;
    }

    public void setContainerACL(String container, ContainerACL acl) throws ServiceException {
        WebResource webResource = getResource().path(container).queryParam("resType", "container").queryParam("comp", "acl");
        webResource = setCanonicalizedResource(webResource, container, "acl");

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-blob-public-access", acl.getPublicAccess());

        ContainerACL.SignedIdentifiers si = new ContainerACL.SignedIdentifiers();
        si.setSignedIdentifiers(acl.getSignedIdentifiers());
        // Note: Add content type here to enable proper HMAC signing
        builder.type("application/xml").put(si);
    }

    public void setContainerMetadata(String container, HashMap<String, String> metadata) throws ServiceException {
        setContainerMetadata(container, metadata, new SetContainerMetadataOptions());
    }

    public void setContainerMetadata(String container, HashMap<String, String> metadata, SetContainerMetadataOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).queryParam("resType", "container").queryParam("comp", "metadata");
        webResource = setCanonicalizedResource(webResource, container, "metadata");

        WebResource.Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalMetadataHeader(builder, metadata);
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        // Note: Add content type here to enable proper HMAC signing
        builder.type("text/plain").put("");
    }

    public ListContainersResult listContainers() throws ServiceException {
        return listContainers(new ListContainersOptions());
    }

    public ListContainersResult listContainers(ListContainersOptions options) throws ServiceException {
        WebResource webResource = getResource().path("/").queryParam("comp", "list");
        webResource = setCanonicalizedResource(webResource, null, "list");
        webResource = addOptionalQueryParam(webResource, "prefix", options.getPrefix());
        webResource = addOptionalQueryParam(webResource, "marker", options.getMarker());
        webResource = addOptionalQueryParam(webResource, "maxresults", options.getMaxResults(), 0);
        if (options.getListingDetails().contains(ContainerListingDetails.METADATA)) {
            webResource = webResource.queryParam("include", "metadata");
        }

        Builder builder = webResource.header("x-ms-version", API_VERSION);

        return builder.get(ListContainersResult.class);
    }

    public ListBlobsResult listBlobs(String container) throws ServiceException {
        return listBlobs(container, new ListBlobsOptions());
    }

    public ListBlobsResult listBlobs(String container, ListBlobsOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).queryParam("comp", "list").queryParam("resType", "container");
        webResource = setCanonicalizedResource(webResource, container, "list");
        webResource = addOptionalQueryParam(webResource, "prefix", options.getPrefix());
        webResource = addOptionalQueryParam(webResource, "marker", options.getMarker());
        webResource = addOptionalQueryParam(webResource, "maxresults", options.getMaxResults(), 0);

        if (options.getListingDetails().size() > 0) {
            EnumCommaStringBuilder<BlobListingDetails> sb = new EnumCommaStringBuilder<BlobListingDetails>();

            sb.addValue(options.getListingDetails(), BlobListingDetails.SNAPSHOTS, "snapshots");
            sb.addValue(options.getListingDetails(), BlobListingDetails.UNCOMMITTED_BLOBS, "uncommittedblobs");
            sb.addValue(options.getListingDetails(), BlobListingDetails.METADATA, "metadata");

            webResource = addOptionalQueryParam(webResource, "include", sb.toString());
        }

        Builder builder = webResource.header("x-ms-version", API_VERSION);

        return builder.get(ListBlobsResult.class);
    }

    public void createPageBlob(String container, String blob, int length) throws ServiceException {
        createPageBlob(container, blob, length, new CreateBlobOptions());
    }

    public void createPageBlob(String container, String blob, int length, CreateBlobOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container + "/" + blob);
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, null);

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-blob-type", "PageBlob");
        builder = addOptionalHeader(builder, "Content-Length", 0);
        builder = addOptionalHeader(builder, "x-ms-blob-content-length", length);
        builder = addOptionalHeader(builder, "x-ms-blob-sequence-number", options.getSequenceNumber());
        builder = addPutBlobHeaders(options, builder);

        // Note: Add content type here to enable proper HMAC signing
        builder.type("application/octet-stream").put(new byte[0]);
    }

    public void createBlockBlob(String container, String blob, InputStream contentStream) throws ServiceException {
        createBlockBlob(container, blob, contentStream, new CreateBlobOptions());
    }

    public void createBlockBlob(String container, String blob, InputStream contentStream, CreateBlobOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container + "/" + blob);
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, null);

        Builder builder = webResource.header("x-ms-version", API_VERSION);

        builder = builder.header("x-ms-blob-type", "BlockBlob");
        builder = addPutBlobHeaders(options, builder);

        Object contentObject = (contentStream == null ? new byte[0] : contentStream);
        builder.put(contentObject);
    }

    public BlobProperties getBlobProperties(String container, String blob) throws ServiceException {
        return getBlobProperties(container, blob, new GetBlobPropertiesOptions());
    }

    public BlobProperties getBlobProperties(String container, String blob, GetBlobPropertiesOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob);
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, null);
        webResource = addOptionalQueryParam(webResource, "snapshot", options.getSnapshot());

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        ClientResponse response = builder.method("HEAD", ClientResponse.class);
        ThrowIfError(response);

        return getBlobPropertiesFromResponse(response);
    }

    public GetBlobMetadataResult getBlobMetadata(String container, String blob) throws ServiceException {
        return getBlobMetadata(container, blob, new GetBlobMetadataOptions());
    }

    public GetBlobMetadataResult getBlobMetadata(String container, String blob, GetBlobMetadataOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "metadata");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "metadata");
        webResource = addOptionalQueryParam(webResource, "snapshot", options.getSnapshot());

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        ClientResponse response = builder.get(ClientResponse.class);
        ThrowIfError(response);

        GetBlobMetadataResult properties = new GetBlobMetadataResult();
        properties.setEtag(response.getHeaders().getFirst("ETag"));
        properties.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));
        properties.setMetadata(getMetadataFromHeaders(response));

        return properties;
    }

    public SetBlobPropertiesResult setBlobProperties(String container, String blob, SetBlobPropertiesOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "properties");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "properties");

        WebResource.Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-blob-cache-control", options.getCacheControl());
        builder = addOptionalHeader(builder, "x-ms-blob-content-type", options.getContentType());
        builder = addOptionalHeader(builder, "x-ms-blob-content-md5", options.getContentMD5());
        builder = addOptionalHeader(builder, "x-ms-blob-content-encoding", options.getContentEncoding());
        builder = addOptionalHeader(builder, "x-ms-blob-content-language", options.getContentLanguage());
        builder = addOptionalHeader(builder, "x-ms-blob-content-length", options.getContentLength());
        builder = addOptionalHeader(builder, "x-ms-sequence-number-action", options.getSequenceNumberAction());
        builder = addOptionalHeader(builder, "x-ms-blob-sequence-number", options.getSequenceNumber());
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        // Note: Add content type here to enable proper HMAC signing
        ClientResponse response = builder.type("text/plain").put(ClientResponse.class, "");
        ThrowIfError(response);

        SetBlobPropertiesResult result = new SetBlobPropertiesResult();

        result.setEtag(response.getHeaders().getFirst("ETag"));
        result.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));
        if (response.getHeaders().getFirst("x-ms-blob-sequence-number") != null) {
            result.setSequenceNumber(Long.parseLong(response.getHeaders().getFirst("x-ms-blob-sequence-number")));
        }

        return result;
    }

    public SetBlobMetadataResult setBlobMetadata(String container, String blob, HashMap<String, String> metadata) throws ServiceException {
        return setBlobMetadata(container, blob, metadata, new SetBlobMetadataOptions());
    }

    public SetBlobMetadataResult setBlobMetadata(String container, String blob, HashMap<String, String> metadata, SetBlobMetadataOptions options)
            throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "metadata");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "metadata");

        WebResource.Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalMetadataHeader(builder, metadata);
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        // Note: Add content type here to enable proper HMAC signing
        ClientResponse response = builder.type("text/plain").put(ClientResponse.class, "");
        ThrowIfError(response);

        SetBlobMetadataResult result = new SetBlobMetadataResult();
        result.setEtag(response.getHeaders().getFirst("ETag"));
        result.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));
        return result;
    }

    public Blob getBlob(String container, String blob) throws ServiceException {
        return getBlob(container, blob, new GetBlobOptions());
    }

    public Blob getBlob(String container, String blob, GetBlobOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob);
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, null);
        webResource = addOptionalQueryParam(webResource, "snapshot", options.getSnapshot());

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalRangeHeader(builder, options.getRangeStart(), options.getRangeEnd());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        ClientResponse response = builder.get(ClientResponse.class);
        ThrowIfError(response);

        BlobProperties properties = getBlobPropertiesFromResponse(response);
        Blob blobResult = new Blob();
        blobResult.setProperties(properties);
        blobResult.setContentStream(response.getEntityInputStream());
        return blobResult;
    }

    public void deleteBlob(String container, String blob) throws ServiceException {
        deleteBlob(container, blob, new DeleteBlobOptions());
    }

    public void deleteBlob(String container, String blob, DeleteBlobOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container + "/" + blob);
        webResource = addOptionalQueryParam(webResource, "snapshot", options.getSnapshot());
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, null);

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalHeader(builder, "x-ms-delete-snapshots", options.getDeleteSnaphotsOnly() ? "only" : "include");
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        builder.delete();
    }

    public BlobSnapshot createBlobSnapshot(String container, String blob) throws ServiceException {
        return createBlobSnapshot(container, blob, new CreateBlobSnapshotOptions());
    }

    public BlobSnapshot createBlobSnapshot(String container, String blob, CreateBlobSnapshotOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container + "/" + blob).queryParam("comp", "snapshot");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "snapshot");

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalMetadataHeader(builder, options.getMetadata());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        // Note: Add content type here to enable proper HMAC signing
        ClientResponse response = builder.type("text/plain").put(ClientResponse.class, "");
        ThrowIfError(response);

        BlobSnapshot blobSnapshot = new BlobSnapshot();
        blobSnapshot.setEtag(response.getHeaders().getFirst("ETag"));
        blobSnapshot.setSnapshot(response.getHeaders().getFirst("x-ms-snapshot"));
        blobSnapshot.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));

        return blobSnapshot;
    }

    public void copyBlob(String destinationContainer, String destinationBlob, String sourceContainer, String sourceBlob) throws ServiceException {
        copyBlob(destinationContainer, destinationBlob, sourceContainer, sourceBlob, new CopyBlobOptions());
    }

    public void copyBlob(String destinationContainer, String destinationBlob, String sourceContainer, String sourceBlob, CopyBlobOptions options) {
        WebResource webResource = getResource().path(destinationContainer).path(destinationBlob);
        webResource = setCanonicalizedResource(webResource, destinationContainer + "/" + destinationBlob, null);

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalHeader(builder, "x-ms-source-lease-id", options.getSourceLeaseId());
        builder = addOptionalHeader(builder, "x-ms-copy-source", getCopyBlobSourceName(sourceContainer, sourceBlob, options));
        builder = addOptionalMetadataHeader(builder, options.getMetadata());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());
        builder = addOptionalSourceAccessContitionHeader(builder, options.getSourceAccessCondition());

        // Note: Add content type here to enable proper HMAC signing
        builder.type("text/plain").put("");
    }

    public String acquireLease(String container, String blob) throws ServiceException {
        return acquireLease(container, blob, new AcquireLeaseOptions());
    }

    public String acquireLease(String container, String blob, AcquireLeaseOptions options) throws ServiceException {
        return putLeaseImpl("acquire", container, blob, null, options);
    }

    public String renewLease(String container, String blob, String leaseId) throws ServiceException {
        return putLeaseImpl("renew", container, blob, leaseId, new AcquireLeaseOptions());
    }

    public void releaseLease(String container, String blob, String leaseId) throws ServiceException {
        putLeaseImpl("release", container, blob, leaseId, new AcquireLeaseOptions());
    }

    public void breakLease(String container, String blob, String leaseId) throws ServiceException {
        putLeaseImpl("break", container, blob, leaseId, new AcquireLeaseOptions());
    }

    private String putLeaseImpl(String leaseAction, String container, String blob, String leaseId, AcquireLeaseOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "lease");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "lease");

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", leaseId);
        builder = addOptionalHeader(builder, "x-ms-lease-action", leaseAction);
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        // Note: Add content type here to enable proper HMAC signing
        ClientResponse response = builder.type("text/plain").put(ClientResponse.class, "");
        ThrowIfError(response);

        return response.getHeaders().getFirst("x-ms-lease-id");
    }

    public CreateBlobPagesResult clearBlobPages(String container, String blob, long rangeStart, long rangeEnd) throws ServiceException {
        return clearBlobPages(container, blob, rangeStart, rangeEnd, new CreateBlobPagesOptions());
    }

    public CreateBlobPagesResult clearBlobPages(String container, String blob, long rangeStart, long rangeEnd, CreateBlobPagesOptions options)
            throws ServiceException {
        return updatePageBlobPagesImpl("clear", container, blob, rangeStart, rangeEnd, 0, null, options);
    }

    public CreateBlobPagesResult createBlobPages(String container, String blob, long rangeStart, long rangeEnd, long length, InputStream contentStream)
            throws ServiceException {
        return createBlobPages(container, blob, rangeStart, rangeEnd, length, contentStream, new CreateBlobPagesOptions());
    }

    public CreateBlobPagesResult createBlobPages(String container, String blob, long rangeStart, long rangeEnd, long length, InputStream contentStream,
            CreateBlobPagesOptions options) throws ServiceException {
        return updatePageBlobPagesImpl("update", container, blob, rangeStart, rangeEnd, length, contentStream, options);
    }

    private CreateBlobPagesResult updatePageBlobPagesImpl(String action, String container, String blob, Long rangeStart, Long rangeEnd, long length,
            InputStream contentStream, CreateBlobPagesOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "page");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "page");

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalRangeHeader(builder, rangeStart, rangeEnd);
        builder = addOptionalHeader(builder, "Content-Length", length);
        builder = addOptionalHeader(builder, "Content-MD5", options.getContentMD5());
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalHeader(builder, "x-ms-page-write", action);
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        // Note: Add content type here to enable proper HMAC signing
        Object content = (contentStream == null ? new byte[0] : contentStream);
        ClientResponse response = builder.type("application/octet-stream").put(ClientResponse.class, content);
        ThrowIfError(response);

        CreateBlobPagesResult result = new CreateBlobPagesResult();
        result.setEtag(response.getHeaders().getFirst("ETag"));
        result.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));
        result.setContentMD5(response.getHeaders().getFirst("Content-MD5"));
        result.setSequenceNumber(Long.parseLong(response.getHeaders().getFirst("x-ms-blob-sequence-number")));

        return result;
    }

    public ListBlobRegionsResult listBlobRegions(String container, String blob) throws ServiceException {
        return listBlobRegions(container, blob, new ListBlobRegionsOptions());
    }

    public ListBlobRegionsResult listBlobRegions(String container, String blob, ListBlobRegionsOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "pagelist");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "pagelist");

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalRangeHeader(builder, options.getRangeStart(), options.getRangeEnd());
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        ClientResponse response = builder.get(ClientResponse.class);
        ThrowIfError(response);

        ListBlobRegionsResult result = response.getEntity(ListBlobRegionsResult.class);
        result.setEtag(response.getHeaders().getFirst("ETag"));
        result.setContentLength(Long.parseLong(response.getHeaders().getFirst("x-ms-blob-content-length")));
        result.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));

        return result;
    }

    public void createBlobBlock(String container, String blob, String blockId, InputStream contentStream) throws ServiceException {
        createBlobBlock(container, blob, blockId, contentStream, new CreateBlobBlockOptions());
    }

    public void createBlobBlock(String container, String blob, String blockId, InputStream contentStream, CreateBlobBlockOptions options)
            throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "block");
        webResource = addOptionalQueryParam(webResource, "blockid", new String(Base64.encode(blockId)));
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "block");

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalHeader(builder, "Content-MD5", options.getContentMD5());

        // Note: Add content type here to enable proper HMAC signing
        builder.type("application/octet-stream").put(contentStream);
    }

    public void commitBlobBlocks(String container, String blob, BlockList blockList) throws ServiceException {
        commitBlobBlocks(container, blob, blockList, new CommitBlobBlocksOptions());
    }

    public void commitBlobBlocks(String container, String blob, BlockList blockList, CommitBlobBlocksOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "blocklist");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "blocklist");

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());
        builder = addOptionalHeader(builder, "x-ms-blob-cache-control", options.getBlobCacheControl());
        builder = addOptionalHeader(builder, "x-ms-blob-content-type", options.getBlobContentType());
        builder = addOptionalHeader(builder, "x-ms-blob-content-encoding", options.getBlobContentEncoding());
        builder = addOptionalHeader(builder, "x-ms-blob-content-language", options.getBlobContentLanguage());
        builder = addOptionalHeader(builder, "x-ms-blob-content-md5", options.getBlobContentMD5());
        builder = addOptionalMetadataHeader(builder, options.getMetadata());
        builder = addOptionalAccessContitionHeader(builder, options.getAccessCondition());

        // Note: Add content type here to enable proper HMAC signing
        builder.type("application/xml").put(blockList);
    }

    public ListBlobBlocksResult listBlobBlocks(String container, String blob) throws ServiceException {
        return listBlobBlocks(container, blob, new ListBlobBlocksOptions());
    }

    public ListBlobBlocksResult listBlobBlocks(String container, String blob, ListBlobBlocksOptions options) throws ServiceException {
        WebResource webResource = getResource().path(container).path(blob).queryParam("comp", "blocklist");
        webResource = setCanonicalizedResource(webResource, container + "/" + blob, "blocklist");
        webResource = addOptionalQueryParam(webResource, "snapshot", options.getSnapshot());
        webResource = addOptionalQueryParam(webResource, "blocklisttype", options.getListType());

        Builder builder = webResource.header("x-ms-version", API_VERSION);
        builder = addOptionalHeader(builder, "x-ms-lease-id", options.getLeaseId());

        ClientResponse response = builder.get(ClientResponse.class);
        ThrowIfError(response);

        ListBlobBlocksResult result = response.getEntity(ListBlobBlocksResult.class);
        result.setEtag(response.getHeaders().getFirst("ETag"));
        result.setContentType(response.getHeaders().getFirst("Content-Type"));
        result.setContentLength(Long.parseLong(response.getHeaders().getFirst("x-ms-blob-content-length")));
        result.setLastModified(dateMapper.parseNoThrow(response.getHeaders().getFirst("Last-Modified")));

        return result;
    }
}
