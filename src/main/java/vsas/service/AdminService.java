package vsas.service;

import java.util.List;
import vsas.model.User;
import vsas.model.UserType;
import vsas.model.ScrollStatistics;

public interface AdminService {
    // TODO UM-05: bootstrap one initial admin securely; permit additional admins.
    void initialiseAdmin(String username, char[] password);
    // TODO UM-05: enforce admin authorization on every operation below.
    List<User> listUsers(User actor);
    User addUser(User actor, String username, char[] password,
                 String fullName, String email, String phone, UserType type);
    User addUser(User actor, String idKey, String username, char[] password,
                 String fullName, String email, String phone, UserType type);
    void deleteUser(User actor, String targetIdKey);
    List<ScrollStatistics> statistics(User actor);
}
