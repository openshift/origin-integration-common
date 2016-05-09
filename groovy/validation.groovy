#!/usr/bin/groovy
import com.openshift.restclient.ClientFactory
import com.openshift.restclient.ResourceKind
import com.openshift.restclient.authorization.TokenAuthorizationStrategy
import com.openshift.restclient.authorization.ResourceForbiddenException

def _main(args) {
    def command = args[0]
    args = args.drop(1)
    if(command == "check_exists") {
        if(!command_check_exists(args))
            System.exit 1
    } else if(command == "check_edit_role") {
        if(!command_check_edit_role(*args))
            System.exit 1
    } else if(command == "check_cluster_reader") {
        if(!command_check_cluster_reader(*args))
            System.exit 1
    }
}

def get_client() {
    final TOKEN_FILE = "/var/run/secrets/kubernetes.io/serviceaccount"
    def token = System.getenv()["OC_TOKEN"]
    if(!token)
        token = new File(TOKEN_FILE, "token").text
    return get_client(token)
}

def get_client(token) {
    def master_url = System.getenv()["MASTER_URL"]
    if(!master_url)
        master_url = "https://kubernetes.default.svc.cluster.local"
    return get_client(token, master_url)
}

def get_client(token, master_url) {
    // TODO ISSLCertificateCallback
    def client = new ClientFactory().create(master_url, null)
    client.setAuthorizationStrategy(new TokenAuthorizationStrategy(token))
    return client
}

def command_check_exists(args) {
    if(args.size() < 2)
        System.exit 1
    def client = get_client()
    def (type, namespace) = args
    args = args.drop(2)
    if(args && args[0].equals("--selector"))
        return check_exists_label(client, type, namespace, args[1])
    return check_exists(client, type, namespace, args)
}

def check_exists_label(client, namespace, type, label) {
    def split = label.split("=")
    def list = client.list(
        parseResourceKind(type), namespace, [(split[0]): split[1]])
    print_result(list)
    if(!list.empty)
        return true
    System.err.print(
        "Error getting the $type with selector $label.\n"
        + "Found: ${list.collect {it.metadata.name}}\n"
        + "The API object(s) must exist for a valid deployment.\n")
    return false;
}

def check_exists(client, namespace, type, names) {
    def set = names as Set
    def list = client.list(parseResourceKind(type), namespace)
    if(names.empty) {
        if(!list.empty) {
            print_result(list)
            return true;
        }
    } else {
        list.each {set.remove(it.metadata.name)}
        if(set.empty) {
            print_result(list)
            return true;
        }
    }
    System.err.print(
        "Error getting the $type ${names}.\n"
        + "Found: ${list.collect {it.metadata.name}}\n"
        + "The API object(s) must exist for a valid deployment.\n")
    return false;
}

def parseResourceKind(s) {
    def ret = [
        deploymentconfigs: ResourceKind.DEPLOYMENT_CONFIG,
        imagestreams: ResourceKind.IMAGE_STREAM,
        routes: ResourceKind.ROUTE,
        serviceaccounts: ResourceKind.SERVICE_ACCOUNT,
        services: ResourceKind.SERVICE,
    ][s]
    if(ret)
        return ret
    System.err.println("Invalid resource kind: $s")
    System.exit 2
}

def print_result(r) {
    println r.collect{it.metadata.name}.join(" ")
}

def command_check_edit_role(namespace) {
    def client = get_client()
    // Inability to access SAs indicates that we didn't get the edit role.
    // It's not a perfect test but will catch those who fail to follow
    // directions.
    try {
        client.list(ResourceKind.SERVICE_ACCOUNT, namespace)
    } catch(ResourceForbiddenException) {
        System.err.print(
            "Service account does not have expected access in the $project"
            + " project.\nGive it edit access with:\n"
            + "  \$ oc policy add-role-to-user edit -z <name>\n")
        return false
    }
    return true;
}

def command_check_cluster_reader(namespace, account) {
    def client = get_client()
    def secrets = client.list(ResourceKind.SECRET, namespace)
    def token = secrets.find {
        it.type == "kubernetes.io/service-account-token" \
            && it.annotations["kubernetes.io/service-account.name"] == account
    }
    token = new String(token.data.token.asString().decodeBase64())
    // Just reading nodes isn't enough, but lack of access is a good indicator.
    try {
        // XXX openshift-restclient-java patch
        get_client(token).list("Node")
    } catch(ResourceForbiddenException) {
        System.err.print(
            "The $account ServiceAccount does not have the required"
            + " access.\nGive it cluster-reader access with:\n"
            + "  \$ oadm policy add-cluster-role-to-user cluster-reader"
            + " system:serviceaccount:$namespace:$account\n")
        return false
    }
    return true
}

_main(args)
