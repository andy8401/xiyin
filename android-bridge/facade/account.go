package facade

import (
	"fmt"
	"strings"

	"mygo-music/internal/music"
	"rsc.io/qr"
)

// A listener's account is a plugin export (signIn / signOut / account), not a
// host feature. Nothing on Android ever called signIn before this file existed,
// which is exactly why 网易云 / QQ音乐 / 喜马拉雅 could not bring up a sign-in
// page: those plugins only ask the host for one from *inside* signIn(), and a
// plugin that pushes a QR code had nobody to push it to.
//
// SignInJSON blocks for as long as the listener takes - the plugin allows five
// minutes - so Kotlin must call it off the main thread. MusicRepository does.

// accountView is what the UI renders: who is signed in, if anyone.
type accountView struct {
	SignedIn bool   `json:"signedIn"`
	Name     string `json:"name,omitempty"`
	ImageURL string `json:"imageUrl,omitempty"`
	Page     string `json:"page,omitempty"`
}

func toAccountView(a *music.Account) accountView {
	if a == nil {
		return accountView{}
	}
	return accountView{SignedIn: true, Name: a.Name, ImageURL: a.ImageURL, Page: a.Page}
}

// AccountJSON says who is signed in to a source.
//
// A source with signIn but no account export cannot be asked who it is, so it
// answers "nobody": the UI then offers the sign-in button instead of showing a
// stale name.
func AccountJSON(pluginID string) (string, error) {
	b.mu.Lock()
	p, err := pluginByID(pluginID)
	b.mu.Unlock()
	if err != nil {
		return "", err
	}
	if !p.Can("account") {
		return jsonMarshal(accountView{})
	}
	a, err := p.Account()
	if err != nil {
		return "", err
	}
	return jsonMarshal(toAccountView(a))
}

// SignInJSON runs a source's sign-in.
//
// Two ways a plugin signs in, both already supported by the host bridge and
// both now reachable:
//   - host.webSignIn: a page in a WebView (ximalaya-web's passport page).
//   - host.signInPrompt: a QR code for the phone to scan (Netease Cloud Music,
//     QQ Music). The QR is encoded here so the UI needs no QR library of its
//     own: the matrix crosses JNI as rows of "0"/"1".
//
// The prompt event with no qr/matrix clears the card, whether the sign-in
// worked or failed.
//
// NOTE: this comment is copied into the generated Java by gomobile, and javac
// reads it as GBK on a Chinese Windows console. Keep exported doc comments
// ASCII-only or the AAR build fails with "unmappable character for encoding
// GBK" - it has nothing to do with the code itself.
func SignInJSON(pluginID string) (string, error) {
	b.mu.Lock()
	p, err := pluginByID(pluginID)
	busy := b.signingIn[pluginID]
	b.mu.Unlock()
	if err != nil {
		return "", err
	}
	if !p.Can("signIn") {
		return "", fmt.Errorf("这个音源不需要登录")
	}
	if busy {
		return "", fmt.Errorf("正在登录，请稍候")
	}

	b.mu.Lock()
	if b.signingIn == nil {
		b.signingIn = map[string]bool{}
	}
	b.signingIn[pluginID] = true
	b.mu.Unlock()
	defer func() {
		b.mu.Lock()
		delete(b.signingIn, pluginID)
		b.mu.Unlock()
		p.OnSignInPrompt(nil)
	}()

	p.OnSignInPrompt(func(prompt music.SignInPrompt) {
		event := map[string]any{"event": "signInPrompt", "plugin": pluginID, "text": prompt.Text}
		if prompt.QR != "" {
			event["qr"] = prompt.QR
			if code, err := qr.Encode(prompt.QR, qr.M); err == nil {
				event["matrix"] = qrMatrix(code)
			}
			// An unencodable QR keeps its text, which says how else to go on.
		}
		notify(event)
	})

	acct, err := p.SignIn()
	notify(map[string]any{"event": "signInPrompt", "plugin": pluginID})
	if err != nil {
		return "", err
	}
	notify(map[string]any{"event": "account", "plugin": pluginID, "signedIn": acct != nil})
	return jsonMarshal(toAccountView(acct))
}

// SignOutJSON forgets the account. A source without signOut is already signed
// out as far as the UI is concerned, so that is not an error.
func SignOutJSON(pluginID string) (string, error) {
	b.mu.Lock()
	p, err := pluginByID(pluginID)
	b.mu.Unlock()
	if err != nil {
		return "", err
	}
	if p.Can("signOut") {
		if err := p.SignOut(); err != nil {
			return "", err
		}
	}
	notify(map[string]any{"event": "account", "plugin": pluginID, "signedIn": false})
	return jsonMarshal(accountView{})
}

// qrMatrix turns an encoded QR into rows of "1" (black) and "0" (white), the
// only shape a gomobile signature can carry without an image type.
func qrMatrix(code *qr.Code) []string {
	rows := make([]string, code.Size)
	for y := 0; y < code.Size; y++ {
		var row strings.Builder
		row.Grow(code.Size)
		for x := 0; x < code.Size; x++ {
			if code.Black(x, y) {
				row.WriteByte('1')
			} else {
				row.WriteByte('0')
			}
		}
		rows[y] = row.String()
	}
	return rows
}
