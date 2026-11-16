import { useEffect, useState } from "preact/hooks";

export interface Store<T> {
  get(): T;
  set(patch: Partial<T>): void;
  subscribe(cb: (s: T) => void): () => void;
}

export function createStore<T extends object>(initial: T): Store<T> {
  let state = initial;
  const subs = new Set<(s: T) => void>();
  return {
    get: () => state,
    set(patch) {
      state = { ...state, ...patch };
      subs.forEach((cb) => cb(state));
    },
    subscribe(cb) {
      subs.add(cb);
      return () => subs.delete(cb);
    },
  };
}

export function useStore<T>(store: Store<T>): T {
  const [s, setS] = useState(store.get());
  useEffect(() => store.subscribe(setS), [store]);
  return s;
}
