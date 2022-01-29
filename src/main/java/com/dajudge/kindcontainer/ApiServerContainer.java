/*
Copyright 2020-2022 Alex Stockinger

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.dajudge.kindcontainer;

import com.dajudge.kindcontainer.pki.CertAuthority;
import com.dajudge.kindcontainer.pki.KeyStoreWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.dockerjava.api.command.CreateContainerCmd;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.jetbrains.annotations.NotNull;
import org.testcontainers.containers.Network;
import org.testcontainers.shaded.com.google.common.io.Files;
import org.testcontainers.shaded.org.bouncycastle.asn1.x509.GeneralName;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static com.dajudge.kindcontainer.Utils.createNetwork;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.asList;
import static org.testcontainers.utility.MountableFile.forHostPath;

public class ApiServerContainer<T extends ApiServerContainer<T>> extends KubernetesContainer<T> {
    private static final String API_SERVER_IMAGE = "k8s.gcr.io/kube-apiserver:v1.21.1";
    private static final String PKI_BASEDIR = "/etc/kubernetes/pki";
    private static final String ETCD_PKI_BASEDIR = PKI_BASEDIR + "/etcd";
    private static final String ETCD_CLIENT_KEY = ETCD_PKI_BASEDIR + "/etcd/apiserver-client.key";
    private static final String ETCD_CLIENT_CERT = ETCD_PKI_BASEDIR + "/etcd/apiserver-client.crt";
    private static final String ETCD_CLIENT_CA = ETCD_PKI_BASEDIR + "/etcd/ca.crt";
    private static final String API_SERVER_CA = PKI_BASEDIR + "/ca.crt";
    private static final String API_SERVER_CERT = PKI_BASEDIR + "/apiserver.crt";
    private static final String API_SERVER_KEY = PKI_BASEDIR + "/apiserver.key";
    private static final String API_SERVER_PUBKEY = PKI_BASEDIR + "/apiserver.pub";
    private static final String DOCKER_BASE_PATH = "/docker";
    private static final String IP_ADDRESS_PATH = DOCKER_BASE_PATH + "/ip.txt";
    private static final String ETCD_HOSTNAME_PATH = DOCKER_BASE_PATH + "/etcd.txt";
    private static final String INTERNAL_HOSTNAME = "apiserver";
    private static final int INTERNAL_API_SERVER_PORT = 6443;
    private static final File tempDir = Files.createTempDir();
    private final CertAuthority apiServerCa = new CertAuthority(System::currentTimeMillis, "CN=API Server CA");
    private final EtcdContainer etcd;
    private final Config config = Config.empty();

    public ApiServerContainer() {
        this(API_SERVER_IMAGE);
    }

    public ApiServerContainer(final String apiServerImage) {
        this(DockerImageName.parse(apiServerImage));
    }

    public ApiServerContainer(final DockerImageName apiServerImage) {
        super(apiServerImage);
        final Network network = createNetwork();
        etcd = new EtcdContainer(network);
        this
                .withCreateContainerCmdModifier(this::createContainerCmdModifier)
                .withEnv("ETCD_CLIENT_KEY", ETCD_CLIENT_KEY)
                .withEnv("ETCD_CLIENT_CERT", ETCD_CLIENT_CERT)
                .withEnv("ETCD_CLIENT_CA", ETCD_CLIENT_CA)
                .withEnv("API_SERVER_CA", API_SERVER_CA)
                .withEnv("API_SERVER_CERT", API_SERVER_CERT)
                .withEnv("API_SERVER_KEY", API_SERVER_KEY)
                .withEnv("API_SERVER_PUBKEY", API_SERVER_PUBKEY)
                .withEnv("IP_ADDRESS_PATH", IP_ADDRESS_PATH)
                .withEnv("ETCD_HOSTNAME_PATH", ETCD_HOSTNAME_PATH)
                .withExposedPorts(INTERNAL_API_SERVER_PORT)
                .waitingFor(new WaitForExternalPortStrategy(INTERNAL_API_SERVER_PORT))
                .withNetwork(network)
                .withNetworkAliases(INTERNAL_HOSTNAME);
    }

    @Override
    public String getInternalHostname() {
        return INTERNAL_HOSTNAME;
    }

    @Override
    public int getInternalPort() {
        return INTERNAL_API_SERVER_PORT;
    }

    private void createContainerCmdModifier(final CreateContainerCmd cmd) {
        cmd.withEntrypoint();
        final List<String> params = new HashMap<String, String>() {{
            put("advertise-address", Utils.resolve(getHost()));
            put("allow-privileged", "true");
            put("authorization-mode", "Node,RBAC");
            put("enable-admission-plugins", "NodeRestriction");
            put("enable-bootstrap-token-auth", "true");
            put("client-ca-file", API_SERVER_CA);
            put("tls-cert-file", API_SERVER_CERT);
            put("tls-private-key-file", API_SERVER_KEY);
            put("kubelet-client-certificate", API_SERVER_CERT);
            put("kubelet-client-key", API_SERVER_KEY);
            put("proxy-client-key-file", API_SERVER_KEY);
            put("proxy-client-cert-file", API_SERVER_CERT);
            put("etcd-cafile", ETCD_CLIENT_CA);
            put("etcd-certfile", ETCD_CLIENT_CERT);
            put("etcd-keyfile", ETCD_CLIENT_KEY);
            put("etcd-servers", format("https://%s:2379", etcd.getEtcdIpAddress()));
            put("service-account-key-file", API_SERVER_PUBKEY);
            put("service-account-signing-key-file", API_SERVER_KEY);
            put("service-account-issuer", "https://kubernetes.default.svc.cluster.local");
            put("kubelet-preferred-address-types", "InternalIP,ExternalIP,Hostname");
            put("requestheader-allowed-names", "front-proxy-client");
            put("requestheader-client-ca-file", API_SERVER_CA);
            put("requestheader-extra-headers-prefix", "X-Remote-Extra-");
            put("requestheader-group-headers", "X-Remote-Group");
            put("requestheader-username-headers", "X-Remote-User");
            put("runtime-config", "");
            put("secure-port", String.format("%d", INTERNAL_API_SERVER_PORT));
            put("service-cluster-ip-range", "10.96.0.0/16");
        }}.entrySet().stream()
                .map(e -> format("--%s=%s", e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        final ArrayList<String> cmdLine = new ArrayList<>();
        cmdLine.add("kube-apiserver");
        cmdLine.addAll(params);
        cmd.withCmd(cmdLine.toArray(new String[]{}));
    }

    @Override
    public DefaultKubernetesClient getClient() {
        return new DefaultKubernetesClient(config);
    }

    @Override
    public void start() {
        try {
            etcd.start();
            final KeyStoreWrapper apiServerKeyPair = writeCertificates();
            super.start();
            configureClient(apiServerKeyPair);
        } catch (final RuntimeException e) {
            etcd.close();
            throw e;
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private KeyStoreWrapper writeCertificates() throws IOException {
        final KeyStoreWrapper apiServerKeyPair = apiServerCa.newKeyPair("O=system:masters,CN=kubernetes-admin", asList(
                new GeneralName(GeneralName.iPAddress, Utils.resolve(getHost())),
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                new GeneralName(GeneralName.dNSName, INTERNAL_HOSTNAME)
        ));
        final KeyStoreWrapper etcdClientKeyPair = etcd.newClientKeypair("CN=API Server");
        final Path apiServerCert = writeTempFile("apiServer.crt", apiServerKeyPair.getCertificatePem());
        final Path apiServerKey = writeTempFile("apiServer.key", apiServerKeyPair.getPrivateKeyPem());
        final Path apiServerPubkey = writeTempFile("apiServer.pub", apiServerKeyPair.getPublicKeyPem());
        final Path apiServerCaCert = writeTempFile("apiServer.ca.crt", apiServerCa.getCaKeyStore().getCertificatePem());
        final Path etcdCert = writeTempFile("etcd.crt", etcdClientKeyPair.getCertificatePem());
        final Path etcdKey = writeTempFile("etcd.key", etcdClientKeyPair.getPrivateKeyPem());
        final Path etcdCaCert = writeTempFile("etcd.ca.crt", etcd.getCaCertificatePem());

        withCopyFileToContainer(forHostPath(apiServerCert), API_SERVER_CERT);
        withCopyFileToContainer(forHostPath(apiServerKey), API_SERVER_KEY);
        withCopyFileToContainer(forHostPath(apiServerCaCert), API_SERVER_CA);
        withCopyFileToContainer(forHostPath(apiServerPubkey), API_SERVER_PUBKEY);
        withCopyFileToContainer(forHostPath(etcdCert), ETCD_CLIENT_CERT);
        withCopyFileToContainer(forHostPath(etcdKey), ETCD_CLIENT_KEY);
        withCopyFileToContainer(forHostPath(etcdCaCert), ETCD_CLIENT_CA);
        return apiServerKeyPair;
    }

    private Path writeTempFile(final String filename, final String data) throws IOException {
        final File file = new File(tempDir, filename);
        Files.write(data.getBytes(US_ASCII), file);
        return file.toPath();
    }

    private void configureClient(final KeyStoreWrapper apiServerKeyPair) {
        config.setCaCertData(base64(apiServerCa.getCaKeyStore().getCertificatePem()));
        config.setClientCertData(base64(apiServerKeyPair.getCertificatePem()));
        config.setClientKeyData(base64(apiServerKeyPair.getPrivateKeyPem()));
        config.setMasterUrl(format("https://%s:%d", getContainerIpAddress(), getMappedPort(INTERNAL_API_SERVER_PORT)));
        config.setConnectionTimeout(10000);
        config.setRequestTimeout(60000);
    }


    private String base64(final String str) {
        return Base64.getEncoder().encodeToString(str.getBytes(US_ASCII));
    }

    @Override
    public String getInternalKubeconfig() {
        try {
            final String url = format("https://%s:%d", INTERNAL_HOSTNAME, INTERNAL_API_SERVER_PORT);
            return Serialization.yamlMapper().writeValueAsString(new io.fabric8.kubernetes.api.model.ConfigBuilder()
                    .withClusters(new NamedClusterBuilder()
                            .withName("kindcontainer")
                            .withCluster(new ClusterBuilder()
                                    .withServer(url)
                                    .withCertificateAuthorityData(config.getCaCertData())
                                    .build())
                            .build())
                    .withCurrentContext("kindcontainer")
                    .withContexts(new NamedContextBuilder()
                            .withName("kindcontainer")
                            .withNewContext()
                            .withCluster("kindcontainer")
                            .withUser("kindcontainer")
                            .endContext()
                            .build())
                    .withUsers(new NamedAuthInfoBuilder()
                            .withName("kindcontainer")
                            .withUser(new AuthInfoBuilder()
                                    .withClientCertificateData(config.getClientCertData())
                                    .withClientKeyData(config.getClientKeyData())
                                    .build())
                            .build())
                    .build());
        } catch (final JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize kubeconfig");
        }
    }

    @Override
    public void stop() {
        super.stop();
        etcd.stop();
    }
}
