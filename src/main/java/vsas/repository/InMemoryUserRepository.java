package vsas.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vsas.model.User;

/** Thread-safe repository used for tests and short-lived embedded servers. */
public final class InMemoryUserRepository implements UserRepository {
    private final Map<String, User> users = new LinkedHashMap<>();

    @Override
    public synchronized Optional<User> findById(String idKey) {
        return Optional.ofNullable(users.get(idKey));
    }

    @Override
    public synchronized Optional<User> findByUsername(String username) {
        return users.values().stream().filter(user -> user.username().equals(username)).findFirst();
    }

    @Override
    public synchronized List<User> findAll() {
        return List.copyOf(users.values());
    }

    @Override
    public synchronized void save(User user) {
        users.put(user.idKey(), user);
    }

    @Override
    public synchronized void delete(String idKey) {
        users.remove(idKey);
    }
}
