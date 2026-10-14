//! Plain type definitions for the action registry (`docs/agent/MASTER_PLAN_ADDENDUM_1.md` §C1,
//! `docs/agent/DESIGN-MC0-ACTION-REGISTRY.md` §3). MC.0 is data only: `ActionId` and its grammar,
//! and the data half of an action (`ActionDef`, `Placement`, `ConfirmPolicy`, `ActionBody`,
//! `Origin`, `KeyChord`, `GestureId`, `TargetKind`), mirroring the Kotlin `ActionDef` field for
//! field so a future MC.1 TOML load can build one directly. No serde, no logic, no dependencies --
//! serde derives matching §C4's TOML shapes are MC.1's, once those shapes are decided; adding them
//! now would bake in a guess.
//!
//! [`KeyChord`] carries its key as a plain `String` name (`"A"`, `"Escape"`, `"F5"`) rather than
//! the Kotlin type's `androidx.compose.ui.input.key.Key`, which is Android/Compose-only and has no
//! meaning in a crate this workspace also builds for Ubuntu Touch and Linux (`docs/agent/
//! MASTER_PLAN.md` §4.1). [`Placement::Gesture`] carries only the [`GestureId`]: the Kotlin
//! `Placement.Gesture.targetWhen` predicate is Kotlin-only logic, the same way `visibleWhen`/
//! `enabledWhen` live only on the Kotlin-side `BuiltInBinding`, never on the plain `ActionDef`.

/// `<namespace>.<segment>(.<segment>)*`; namespace is `fylz`, `user`, or a bundle id, each shaped
/// like any other segment: `[a-z0-9]+(-[a-z0-9]+)*`. Same grammar and cases as the Kotlin
/// `ActionId.parse()` test.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ActionId(String);

impl ActionId {
    pub fn is_valid(value: &str) -> bool {
        let parts: Vec<&str> = value.split('.').collect();
        parts.len() >= 2 && parts.iter().all(|part| is_valid_segment(part))
    }

    pub fn parse(value: &str) -> Result<ActionId, String> {
        if Self::is_valid(value) {
            Ok(ActionId(value.to_string()))
        } else {
            Err(format!("Invalid action id: {value}"))
        }
    }

    pub fn value(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for ActionId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

fn is_valid_segment(segment: &str) -> bool {
    let bytes = segment.as_bytes();
    if bytes.is_empty() || bytes[0] == b'-' || bytes[bytes.len() - 1] == b'-' {
        return false;
    }
    let mut previous_was_hyphen = false;
    for &byte in bytes {
        match byte {
            b'a'..=b'z' | b'0'..=b'9' => previous_was_hyphen = false,
            b'-' => {
                if previous_was_hyphen {
                    return false;
                }
                previous_was_hyphen = true;
            }
            _ => return false,
        }
    }
    true
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IconRef {
    Builtin(String),
    Bundle(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfirmPolicy {
    None,
    Always,
    IfCountAbove(u32),
    IfDestructive,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Origin {
    BuiltIn,
    Bundle { id: String, version: String },
    Local,
}

/// `Steps`/`Script` are type-only placeholders in MC.0, exactly like their Kotlin counterparts --
/// MC.5 gives `Steps` a step list, MC.7 gives `Script` a script reference.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ActionBody {
    BuiltIn { handler_id: String },
    Steps,
    Script,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TargetKind {
    Entry,
    File,
    Tab,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum GestureId {
    EdgeLeft,
    EdgeRight,
    EdgeBottom,
    Shake,
    ItemTap,
    ItemLongPress,
    ItemDoubleTap,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Bar {
    TopAppBar,
    BrowserRow,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MenuId {
    Overflow,
    Sort,
    ArchiveTools,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RoomId {
    Locations,
    LibraryRail,
    Tools,
    Recovery,
}

/// One key model; `ctrl` and `meta` are kept distinct -- nothing binds `meta` in MC.0, but a
/// future Ubuntu Touch/Linux desktop chord will need it.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct KeyChord {
    pub key: String,
    pub ctrl: bool,
    pub shift: bool,
    pub alt: bool,
    pub meta: bool,
}

impl KeyChord {
    pub fn new(key: impl Into<String>) -> Self {
        KeyChord {
            key: key.into(),
            ctrl: false,
            shift: false,
            alt: false,
            meta: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Placement {
    SelectionBar { order: i32 },
    Toolbar { bar: Bar, order: i32 },
    Menu { menu: MenuId, order: i32 },
    Room { room: RoomId, order: i32 },
    ContextMenu { group: String, order: i32 },
    CommandPalette,
    Shortcut { chord: KeyChord },
    Gesture { gesture: GestureId },
    QuickSettingsTile,
    HomeCard,
}

/// The data half of an action -- field for field with the Kotlin `ActionDef` (design §2.2/§3).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ActionDef {
    pub id: ActionId,
    pub title_key: String,
    pub icon: IconRef,
    pub when_expr: Option<String>,
    pub placements: Vec<Placement>,
    pub confirm: ConfirmPolicy,
    pub body: ActionBody,
    pub origin: Origin,
    pub destructive: bool,
    pub requires_target: Option<TargetKind>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_ids_parse() {
        for id in [
            "fylz.copy",
            "fylz.copy-to",
            "fylz.select.clear",
            "fylz.customisation.problems",
            "user.resize-for-web",
            "acme.my-bundle.action",
            "fylz.sort.folders-first",
            "fylz.7zip",
        ] {
            assert!(ActionId::is_valid(id), "{id}");
            assert_eq!(ActionId::parse(id).unwrap().value(), id);
        }
    }

    #[test]
    fn invalid_ids_are_rejected() {
        for id in [
            "",
            "fylz",
            "Fylz.Copy",
            "fylz.",
            ".copy",
            "fylz..copy",
            "fylz.-copy",
            "fylz.copy-",
            "fylz.co py",
            "fylz.co_py",
        ] {
            assert!(!ActionId::is_valid(id), "{id}");
            assert!(ActionId::parse(id).is_err(), "{id}");
        }
    }

    #[test]
    fn a_def_builds_with_every_plain_type() {
        let def = ActionDef {
            id: ActionId::parse("fylz.copy").unwrap(),
            title_key: "Copy".to_string(),
            icon: IconRef::Builtin("ContentCopy".to_string()),
            when_expr: None,
            placements: vec![
                Placement::SelectionBar { order: 20 },
                Placement::Shortcut {
                    chord: KeyChord {
                        key: "C".to_string(),
                        ctrl: true,
                        shift: false,
                        alt: false,
                        meta: false,
                    },
                },
            ],
            confirm: ConfirmPolicy::None,
            body: ActionBody::BuiltIn {
                handler_id: "fylz.copy".to_string(),
            },
            origin: Origin::BuiltIn,
            destructive: false,
            requires_target: None,
        };
        assert_eq!(def.id.value(), "fylz.copy");
        assert_eq!(def.placements.len(), 2);
    }
}
