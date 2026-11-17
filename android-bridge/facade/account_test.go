package facade

import (
	"encoding/json"
	"strings"
	"testing"

	"rsc.io/qr"
)

// initForAccount wires a listener it can inspect, so the tests see the events
// the car UI reacts to.
func initForAccount(t *testing.T) *testListener {
	t.Helper()
	resetBridge(t)
	t.Cleanup(func() { resetBridge(t) })
	l := &testListener{}
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(l)
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatalf("Init: %v", err)
	}
	return l
}

type accountProbe struct {
	SignedIn bool   `json:"signedIn"`
	Name     string `json:"name"`
	ImageURL string `json:"imageUrl"`
}

func readAccount(t *testing.T, plugin string) accountProbe {
	t.Helper()
	raw, err := AccountJSON(plugin)
	if err != nil {
		t.Fatalf("AccountJSON(%s): %v", plugin, err)
	}
	var a accountProbe
	if err := json.Unmarshal([]byte(raw), &a); err != nil {
		t.Fatalf("account JSON %q: %v", raw, err)
	}
	return a
}

// Signing in is a plugin export the app never called before: 网易云 / QQ音乐 /
// 喜马拉雅 only ask the host for a page from inside signIn(), so without this
// entry point no sign-in page could ever appear.
func TestSignInSignsInAndReportsTheAccount(t *testing.T) {
	l := initForAccount(t)

	if got := readAccount(t, "demo"); got.SignedIn {
		t.Fatalf("demo starts signed out, got %+v", got)
	}

	raw, err := SignInJSON("demo")
	if err != nil {
		t.Fatalf("SignInJSON: %v", err)
	}
	var after accountProbe
	if err := json.Unmarshal([]byte(raw), &after); err != nil {
		t.Fatalf("sign-in JSON %q: %v", raw, err)
	}
	if !after.SignedIn || after.Name != "Demo Listener" {
		t.Fatalf("sign-in should answer the account, got %+v", after)
	}

	// The account event is what makes the UI swap 登录 for the listener's name.
	var sawAccount bool
	for _, c := range l.changes {
		if strings.Contains(c, `"event":"account"`) && strings.Contains(c, `"signedIn":true`) {
			sawAccount = true
		}
	}
	if !sawAccount {
		t.Fatalf("expected an account event, got %v", l.changes)
	}

	if got := readAccount(t, "demo"); !got.SignedIn || got.Name != "Demo Listener" {
		t.Fatalf("account should persist after sign-in, got %+v", got)
	}

	if _, err := SignOutJSON("demo"); err != nil {
		t.Fatalf("SignOutJSON: %v", err)
	}
	if got := readAccount(t, "demo"); got.SignedIn {
		t.Fatalf("account should be gone after sign-out, got %+v", got)
	}
}

// A source that can't sign in must not be offered 登录, and asking anyway is a
// caller error rather than a plugin failure.
func TestSignInRejectsASourceWithoutSignIn(t *testing.T) {
	initForAccount(t)

	if _, err := SignInJSON("cnradio"); err == nil {
		t.Fatal("cnradio has no signIn export, SignInJSON must fail")
	}
	// No account export either: the UI must read "nobody", not an error.
	if got := readAccount(t, "cnradio"); got.SignedIn {
		t.Fatalf("cnradio answers no account, got %+v", got)
	}
}

// The two exports the UI keys on, surfaced from the plugin list.
func TestPluginListCarriesSignInCapability(t *testing.T) {
	initForAccount(t)

	raw, err := ListPluginsJSON()
	if err != nil {
		t.Fatalf("ListPluginsJSON: %v", err)
	}
	var list []map[string]any
	if err := json.Unmarshal([]byte(raw), &list); err != nil {
		t.Fatalf("plugin list %q: %v", raw, err)
	}
	got := map[string]bool{}
	for _, p := range list {
		id, _ := p["id"].(string)
		signIn, _ := p["signIn"].(bool)
		got[id] = signIn
	}
	if !got["demo"] || !got["ximalaya-web"] {
		t.Fatalf("demo and ximalaya-web both export signIn, got %v", got)
	}
	if got["cnradio"] {
		t.Fatalf("cnradio has no signIn export, got %v", got)
	}
}

// An album card in a source that only exports show() (ximalaya-web) must reach
// show() instead of failing with "not supported by this source".
func TestDetailCandidatesFallBackToShow(t *testing.T) {
	got := detailCandidates(detailAlbum)
	if len(got) != len(detailKinds()) {
		t.Fatalf("every kind should be a candidate once, got %v", got)
	}
	if got[0] != detailAlbum {
		t.Fatalf("the requested kind comes first, got %v", got)
	}
	if got[1] != detailShow {
		t.Fatalf("show is the fallback for a missing album(), got %v", got)
	}
}

// The QR crosses JNI as rows of "0"/"1" because gomobile can't carry an image;
// the UI only needs to draw squares.
func TestQRMatrixIsSquareAndBinary(t *testing.T) {
	code, err := qr.Encode("https://passport.example.com/qr?k=abc", qr.M)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	rows := qrMatrix(code)
	if len(rows) != code.Size {
		t.Fatalf("matrix should have %d rows, got %d", code.Size, len(rows))
	}
	for _, r := range rows {
		if len(r) != code.Size {
			t.Fatalf("row should be %d wide, got %d", code.Size, len(r))
		}
		if strings.Trim(r, "01") != "" {
			t.Fatalf("row should hold only 0/1, got %q", r)
		}
	}
}
