import { StatusBar } from "expo-status-bar";
import { useEffect, useMemo, useState } from "react";
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  TextInput,
  View
} from "react-native";

const API_URL = "http://127.0.0.1:8765";

export default function App() {
  const [terms, setTerms] = useState([]);
  const [selected, setSelected] = useState(null);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);

  const visibleTerms = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return terms;
    return terms.filter((term) => {
      return `${term.title} ${term.summary} ${term.content}`.toLowerCase().includes(needle);
    });
  }, [search, terms]);

  async function loadTerms() {
    setLoading(true);
    const response = await fetch(`${API_URL}/api/terms`);
    const data = await response.json();
    setTerms(data.items || []);
    setSelected(data.items?.[0] || null);
    setLoading(false);
  }

  async function loadSpecial(path) {
    const response = await fetch(`${API_URL}${path}`);
    const data = await response.json();
    setSelected(data);
  }

  useEffect(() => {
    loadTerms().catch(() => setLoading(false));
  }, []);

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar style="dark" />
      <View style={styles.header}>
        <Text style={styles.logo}>LX</Text>
        <View>
          <Text style={styles.title}>Lexidex</Text>
          <Text style={styles.subtitle}>Pokedex de terminos</Text>
        </View>
      </View>

      <TextInput
        value={search}
        onChangeText={setSearch}
        placeholder="Buscar termino"
        style={styles.input}
      />

      <View style={styles.actions}>
        <Pressable style={styles.action} onPress={() => loadSpecial("/api/daily")}>
          <Text style={styles.actionText}>Termino del dia</Text>
        </Pressable>
        <Pressable style={styles.action} onPress={() => loadSpecial("/api/random")}>
          <Text style={styles.actionText}>Random</Text>
        </Pressable>
      </View>

      {loading ? (
        <ActivityIndicator />
      ) : (
        <View style={styles.content}>
          <FlatList
            data={visibleTerms}
            keyExtractor={(item) => item.slug}
            style={styles.list}
            renderItem={({ item }) => (
              <Pressable
                style={[styles.termButton, selected?.slug === item.slug && styles.active]}
                onPress={() => setSelected(item)}
              >
                <Text style={styles.termTitle}>{item.title}</Text>
                <Text numberOfLines={2} style={styles.termSummary}>{item.summary}</Text>
              </Pressable>
            )}
          />

          <View style={styles.card}>
            {selected ? (
              <>
                <Text style={styles.cardTitle}>{selected.title}</Text>
                <Text style={styles.summary}>{selected.summary}</Text>
                <Text style={styles.body}>{selected.content}</Text>
                <Text style={styles.meta}>
                  {(selected.categories || []).join(" / ")}
                </Text>
              </>
            ) : (
              <Text style={styles.meta}>Sin termino seleccionado.</Text>
            )}
          </View>
        </View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: "#f6f7f2",
    padding: 18
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    marginBottom: 16
  },
  logo: {
    width: 46,
    height: 46,
    borderRadius: 8,
    backgroundColor: "#2f7d6d",
    color: "white",
    textAlign: "center",
    textAlignVertical: "center",
    fontWeight: "800",
    fontSize: 18
  },
  title: {
    fontSize: 26,
    fontWeight: "800",
    color: "#172026"
  },
  subtitle: {
    color: "#637078"
  },
  input: {
    borderWidth: 1,
    borderColor: "#dbe0de",
    borderRadius: 8,
    backgroundColor: "white",
    padding: 12,
    marginBottom: 10
  },
  actions: {
    flexDirection: "row",
    gap: 10,
    marginBottom: 14
  },
  action: {
    flex: 1,
    minHeight: 42,
    borderRadius: 8,
    backgroundColor: "#172026",
    alignItems: "center",
    justifyContent: "center"
  },
  actionText: {
    color: "white",
    fontWeight: "700"
  },
  content: {
    flex: 1,
    gap: 12
  },
  list: {
    maxHeight: 230
  },
  termButton: {
    backgroundColor: "white",
    borderWidth: 1,
    borderColor: "#dbe0de",
    borderRadius: 8,
    padding: 12,
    marginBottom: 8
  },
  active: {
    borderColor: "#2f7d6d",
    backgroundColor: "#e8f1ee"
  },
  termTitle: {
    fontWeight: "800",
    color: "#172026"
  },
  termSummary: {
    color: "#637078",
    marginTop: 4
  },
  card: {
    flex: 1,
    backgroundColor: "white",
    borderRadius: 8,
    borderWidth: 1,
    borderColor: "#dbe0de",
    padding: 18
  },
  cardTitle: {
    fontSize: 34,
    fontWeight: "900",
    color: "#172026",
    marginBottom: 10
  },
  summary: {
    fontSize: 17,
    lineHeight: 24,
    color: "#32414a",
    marginBottom: 16
  },
  body: {
    fontSize: 16,
    lineHeight: 24,
    color: "#172026"
  },
  meta: {
    color: "#637078",
    marginTop: 16
  }
});
