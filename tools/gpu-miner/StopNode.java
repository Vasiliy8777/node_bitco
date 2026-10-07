import com.sun.tools.attach.VirtualMachine;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/** Shut down only the launcher-owned Spring application, allowing chainstate flush. */
class StopNode {
    public static void main(String[] args) throws Exception {
        var vm = VirtualMachine.attach(args[0]);
        String address;
        try { address = vm.startLocalManagementAgent(); }
        finally { vm.detach(); }
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            connector.getMBeanServerConnection().invoke(
                    new ObjectName("org.springframework.boot:type=Admin,name=SpringApplication"),
                    "shutdown", new Object[0], new String[0]);
        }
    }
}
