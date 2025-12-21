/*
FirePlayer © 2018

firevideoplayer.com
*/
window.mobileAndTabletCheck = function() {
  let check = false;
  (function(a){if(/(android|bb\d+|meego).+mobile|avantgo|bada\/|blackberry|blazer|compal|elaine|fennec|hiptop|iemobile|ip(hone|od)|iris|kindle|lge |maemo|midp|mmp|mobile.+firefox|netfront|opera m(ob|in)i|palm( os)?|phone|p(ixi|re)\/|plucker|pocket|psp|series(4|6)0|symbian|treo|up\.(browser|link)|vodafone|wap|windows ce|xda|xiino|android|ipad|playbook|silk/i.test(a)||/1207|6310|6590|3gso|4thp|50[1-6]i|770s|802s|a wa|abac|ac(er|oo|s\-)|ai(ko|rn)|al(av|ca|co)|amoi|an(ex|ny|yw)|aptu|ar(ch|go)|as(te|us)|attw|au(di|\-m|r |s )|avan|be(ck|ll|nq)|bi(lb|rd)|bl(ac|az)|br(e|v)w|bumb|bw\-(n|u)|c55\/|capi|ccwa|cdm\-|cell|chtm|cldc|cmd\-|co(mp|nd)|craw|da(it|ll|ng)|dbte|dc\-s|devi|dica|dmob|do(c|p)o|ds(12|\-d)|el(49|ai)|em(l2|ul)|er(ic|k0)|esl8|ez([4-7]0|os|wa|ze)|fetc|fly(\-|_)|g1 u|g560|gene|gf\-5|g\-mo|go(\.w|od)|gr(ad|un)|haie|hcit|hd\-(m|p|t)|hei\-|hi(pt|ta)|hp( i|ip)|hs\-c|ht(c(\-| |_|a|g|p|s|t)|tp)|hu(aw|tc)|i\-(20|go|ma)|i230|iac( |\-|\/)|ibro|idea|ig01|ikom|im1k|inno|ipaq|iris|ja(t|v)a|jbro|jemu|jigs|kddi|keji|kgt( |\/)|klon|kpt |kwc\-|kyo(c|k)|le(no|xi)|lg( g|\/(k|l|u)|50|54|\-[a-w])|libw|lynx|m1\-w|m3ga|m50\/|ma(te|ui|xo)|mc(01|21|ca)|m\-cr|me(rc|ri)|mi(o8|oa|ts)|mmef|mo(01|02|bi|de|do|t(\-| |o|v)|zz)|mt(50|p1|v )|mwbp|mywa|n10[0-2]|n20[2-3]|n30(0|2)|n50(0|2|5)|n7(0(0|1)|10)|ne((c|m)\-|on|tf|wf|wg|wt)|nok(6|i)|nzph|o2im|op(ti|wv)|oran|owg1|p800|pan(a|d|t)|pdxg|pg(13|\-([1-8]|c))|phil|pire|pl(ay|uc)|pn\-2|po(ck|rt|se)|prox|psio|pt\-g|qa\-a|qc(07|12|21|32|60|\-[2-7]|i\-)|qtek|r380|r600|raks|rim9|ro(ve|zo)|s55\/|sa(ge|ma|mm|ms|ny|va)|sc(01|h\-|oo|p\-)|sdk\/|se(c(\-|0|1)|47|mc|nd|ri)|sgh\-|shar|sie(\-|m)|sk\-0|sl(45|id)|sm(al|ar|b3|it|t5)|so(ft|ny)|sp(01|h\-|v\-|v )|sy(01|mb)|t2(18|50)|t6(00|10|18)|ta(gt|lk)|tcl\-|tdg\-|tel(i|m)|tim\-|t\-mo|to(pl|sh)|ts(70|m\-|m3|m5)|tx\-9|up(\.b|g1|si)|utst|v400|v750|veri|vi(rg|te)|vk(40|5[0-3]|\-v)|vm40|voda|vulc|vx(52|53|60|61|70|80|81|83|85|98)|w3c(\-| )|webc|whit|wi(g |nc|nw)|wmlb|wonu|x700|yas\-|your|zeto|zte\-/i.test(a.substr(0,4))) check = true;})(navigator.userAgent||navigator.vendor||window.opera);
  return check;
};

var alertType = 0;
function zAlert(a, o) {
	$(".remodal").remove();
	var e = '<div class="remodal" data-remodal-id="modal"><button data-remodal-action="close" class="remodal-close"></button><h1 style="font-size:20px;font-weight:500;">' + a + "</h1><p>" + o + '</p><br><button data-remodal-action="confirm" class="remodal-confirm">OK</button></div>';
	$("body").append(e), $("[data-remodal-id=modal]").remodal().open()
}

var tryCount = 0;
function checkiz(list, uri) {
	var result = false;

	if(list.length == 0)
		return result;

	for(var i=0;i<list.length;i++) {
		if(uri.indexOf(list[i]) > 0) {
			result = true;
			break;
		}
	}

	return result;
}

function isJSON(str) {
    try {
        return (JSON.parse(str) && !!str);
    } catch (e) {
        return false;
    }
}

function fPing(o, ID) {
	$.ajax({
		type: "POST",
		url: "/download/"+ID+"?do=ping",
		data: {url:$(o).attr('href'),label:$(o).text()}
	});
}

var waitFor = function(selector, callback) {
	var tick = function(){
		var e = document.querySelector(selector);
		if (e) {
			callback(e);
		} else {
			setTimeout(tick, 100);
		}
	};
	tick();
};

function addCssToDocument(css){
	var head = document.getElementsByTagName('head')[0];
	var s = document.createElement('style');
	s.setAttribute('type', 'text/css');
	if (s.styleSheet) {
		s.styleSheet.cssText = css;
	} else {
		s.appendChild(document.createTextNode(css));
	}
	head.appendChild(s);
}

var lastIndex = 0, tryCount2 = 0, completed = false, player_loaded = false, p2p = false;
function loadAssets(list) {
	if(lastIndex == 0) {
		completed = false;
	}

	var src = list[lastIndex];
	if(src.indexOf(".js") != -1) {
		var e = document.createElement('script');
		e.src = (src.substr(0,2) != "ht" && src.substr(0,2) != "//" ? player_base_url + src : src);
		e.type = "text/javascript";
	} else if(src.indexOf(".css") != -1) {
		var e = document.createElement('link');
		e.href = (src.substr(0,2) != "ht" && src.substr(0,2) != "//" ? player_base_url + src : src);
		e.rel = "stylesheet";
	}

	e.onload = function() {
		if(lastIndex + 1 != list.length) {
			tryCount2 = 0;
			lastIndex++;
			loadAssets(list);
		} else if(lastIndex + 1 == list.length) {
			tryCount2 = 0;
			lastIndex = 0;
			completed = true;
		}
	}
	e.onerror = function() {
		if(tryCount2 < 2) {
			tryCount2++;
			loadAssets(list);
		} else if(lastIndex + 1 != list.length) {
			lastIndex++;
			loadAssets(list);
		} else if(lastIndex + 1 == list.length) {
			tryCount2 = 0;
			lastIndex = 0;
			completed = true;
		}
	}

	document.getElementsByTagName('head')[0].appendChild(e);
}

function isMobileOrTablet() {
    var userAgent = navigator.userAgent
        || navigator.vendor
        || window.opera
        || null;

	if (!userAgent)
		return false;
	
	var md = new MobileDetect(userAgent);

	return (md.mobile() || md.tablet() ? true : false);
}

function FirePlayer(ID, videoSettings, AutoStart) {
	var Seeking = true;

    $.ajax({
        type: "POST",
        url: "/player/index.php?data="+ID+"&do=getVideo",
		data: {hash:ID,r:document.referrer},
        success: function(data) {
			if(isJSON(data) == false) {
				switch(alertType) {
					case 0:
						window.alert(data);
						break;
					case 1:
						zAlert("Uyarı", data);
						break;
					default:
						console.log(data);
				}
			} else {
				var jData = JSON.parse(data);

				var videoPlayer = videoSettings.videoPlayer;
				var playerList = ["jwplayer"];
				if(playerList.includes(videoPlayer)) {
					$('#playerbase').html('<div id="player"></div>');
				}

				var list = [], tmpList = [];

				if(videoPlayer == "jwplayer") {
					if(videoSettings.p2p) {
						if(isMobileOrTablet()) {
							list = [videoSettings.jwPlayerURL];
						} else {
							p2p = true;
							list = [
								"https://cdn.jsdelivr.net/npm/p2p-media-loader-core@latest/build/p2p-media-loader-core.min.js",
								"https://cdn.jsdelivr.net/npm/p2p-media-loader-hlsjs@latest/build/p2p-media-loader-hlsjs.min.js",
								"https://cdn.jsdelivr.net/npm/@hola.org/jwplayer-hlsjs@latest/dist/jwplayer.hlsjs.min.js",
								videoSettings.jwPlayerURL,
								"/player/assets/js/hls.js"
							];
/*
							list = [
								"https://cdn.jsdelivr.net/npm/p2p-media-loader-core@latest/build/p2p-media-loader-core.min.js",
								"https://cdn.jsdelivr.net/npm/p2p-media-loader-hlsjs@latest/build/p2p-media-loader-hlsjs.min.js",
								"https://cdn.jsdelivr.net/npm/@hola.org/jwplayer-hlsjs@latest/dist/jwplayer.hlsjs.min.js",
								videoSettings.jwPlayerURL,
								"https://cdn.jsdelivr.net/npm/hls.js@latest"
							];
*/
/*
							list = [
								"/player/assets/js/p2p-media-loader-core.min.js",
								"/player/assets/js/p2p-media-loader-hlsjs.min.js",
								"/player/assets/js/jwplayer.hlsjs.min.js",
								videoSettings.jwPlayerURL,
								"/player/assets/js/hls.js"
							];
*/
						}
					} else {
						list = [videoSettings.jwPlayerURL];
					}
					if(videoSettings.tracks.length && videoSettings.SubtitleManager) {
						list = list.concat([
							"/player/assets/jwplayer/subtitlemanager/SubtitleManager.css",
							"/player/assets/jwplayer/subtitlemanager/SubtitleManager.js?v=4",
						]);
					}

					if(videoSettings.skin.name != "alaska" && videoSettings.skin.url) {
						list = list.concat([videoSettings.skin.url]);
					}
				} else if(videoPlayer == "playerjs") {
					list = [
					/*"/player/assets/playerjs-v1.3.js?" + Math.floor(Math.random() * 999999)*/
					"/player/assets/playerjs_default.js"
					];
				}

				if((videoSettings.downloadFile && videoSettings.downloadType == 1) || videoSettings.attachmentSystem || !jData.hls) {
					list = list.concat([
						"/player/assets/js/cryptojs-aes.min.js",
						"/player/assets/js/cryptojs-aes-format.js",
					]);
				}

				if(completed == false) {
					loadAssets(list);
				}

				var stateCheck = setInterval(function() {
					if(completed == true) {
						clearInterval(stateCheck);

						if(!jData.hls) {
							if(jData.videoSources.length) {
								for(var i=0;i<jData.videoSources.length;i++) {
									jData.videoSources[i].file = CryptoJSAesJson.decrypt(jData.videoSources[i].file, /*videoSettings.ck*/jData.ck);
								}
							}
						}

						if(videoSettings.downloadFile && videoSettings.downloadType == 1 && jData.downloadLinks.length) {
							$('.dropdown-content1').empty();
							var filename;
							for(var i=0;i<jData.downloadLinks.length;i++) {
								filename = CryptoJSAesJson.decrypt(jData.downloadLinks[i].file, /*videoSettings.ck*/jData.ck);
								if(jData.downloadLinks[i].language && jData.downloadLinks[i].language != "und") {
									$('.dropdown-content1').append('<li><a href="'+filename+'" target="_blank" onclick="fPing(this, \''+ID+'\');" class="c-list__item dropdown-item">['+jData.downloadLinks[i].language.toUpperCase()+'] '+jData.downloadLinks[i].label+ (jData.downloadLinks[i].size != '0' ? ' ('+jData.downloadLinks[i].size+')' : '') + '<\/a></li>');
								} else {
									$('.dropdown-content1').append('<li><a href="'+filename+'" target="_blank" onclick="fPing(this, \''+ID+'\');" class="c-list__item dropdown-item">'+jData.downloadLinks[i].label+ (jData.downloadLinks[i].size != '0' ? ' ('+jData.downloadLinks[i].size+')' : '') + '<\/a></li>');
								}
							}
							$('.dropdown1').show();
						}

						if(videoSettings.attachmentSystem && jData.attachmentLinks.length) {
							$('.dropdown-content2').empty();
							var filename;
							for(var i=0;i<jData.attachmentLinks.length;i++) {
								filename = CryptoJSAesJson.decrypt(jData.attachmentLinks[i].file, /*videoSettings.ck*/jData.ck);
								$('.dropdown-content2').append('<li><a href="'+filename+'" target="_blank" onclick="fPing(this, \''+ID+'\');" class="c-list__item dropdown-item">'+jData.attachmentLinks[i].label+'<\/a></li>');
							}
							$('.dropdown2').show();
						}

						switch(videoPlayer) {
							case "jwplayer":
								jwplayer.key = videoSettings.jwPlayerKey;
								FirePlayer_jwplayer8(ID, videoSettings, AutoStart, jData, Seeking);
								break;
							case "playerjs":
								FirePlayer_playerjs(ID, videoSettings, AutoStart, jData, Seeking);
								break;
						}
						if(!player_loaded) {
							player_loaded = true;
						}
					}
				},100);
			}
		}
	});
}

function setCookie(cname, cvalue, exdays) {
    var d = new Date();
    d.setTime(d.getTime() + (exdays*24*60*60*1000));
    var expires = "expires="+ d.toUTCString();
    document.cookie = cname + "=" + cvalue + ";" + expires + ";path=/;SameSite=None;Secure";
}

function getCookie(cname) {
    var name = cname + "=";
    var decodedCookie = decodeURIComponent(document.cookie);
    var ca = decodedCookie.split(';');
    for(var i = 0; i <ca.length; i++) {
        var c = ca[i];
        while (c.charAt(0) == ' ') {
            c = c.substring(1);
        }
        if (c.indexOf(name) == 0) {
            return c.substring(name.length, c.length);
        }
    }
    return "";
}

var get_params = function(search_string) {
  var parse = function(params, pairs) {
    var pair = pairs[0];
    var parts = pair.split('=');
    var key = decodeURIComponent(parts[0]).replace("[]", "");
    var value = decodeURIComponent(parts.slice(1).join('='));
    if (typeof params[key] === "undefined") {
		params[key] = value;
    } else {
		params[key] = [].concat(params[key], value);
    }
    return pairs.length == 1 ? params : parse(params, pairs.slice(1))
  }
  return search_string.length == 0 ? {} : parse({}, search_string.substr(1).split('&'));
}

function SecondsConvert(sec) {
	let totalSeconds = Math.floor(sec);
	let hours = Math.floor(totalSeconds / 3600);
	totalSeconds %= 3600;
	let minutes = Math.floor(totalSeconds / 60);
	let seconds = totalSeconds % 60;

	minutes = String(minutes).padStart(2, "0");
	hours = String(hours).padStart(2, "0");
	seconds = String(seconds).padStart(2, "0");

	return hours + ":" + minutes + ":" + seconds;
}

var bLoaded = false;
var currentAudioTrack = -1;
var currentCaptions = 0;
var audioTracks = [], captionsList = [];

var downloaded_total = 0, downloaded = 0;

var options = {
	useEasing : true, 
	useGrouping : true, 
	duration: 9, 
	suffix: '%'
};
var progress = false;

var Played = false, Once = false, freezeCnt = 0;

var bIntro=0, bOutro=0;

function hmsToSeconds(str) {
    var p = str.split(':'),
        s = 0, m = 1;

    while (p.length > 0) {
        s += m * parseInt(p.pop(), 10);
        m *= 60;
    }

    return s;
}

function FirePlayer_jwplayer8(ID, videoSettings, AutoStart, jData, Seeking) {
	var subtitles = [];
	if(window.location.href.indexOf("?") != -1) {
		var params = get_params(location.search);
		if(params.sub) {
			subtitle = "subtitle";
			if(params.subtitle) {
				if(typeof params.subtitle === "string") {
					subtitle = params.subtitle;
				}
			}
			if(typeof params.sub === "object") {
				for(var i=0;i<params.sub.length;i++) {
					subtitles.push({
						"kind":"captions",
						"file":params.sub[i],
						"label":(subtitle == "subtitle" ? (typeof params.subtitle === "object" && typeof params.subtitle[i] === "string" ? params.subtitle[i] : subtitle) : subtitle),
						"language":null,
						"default":(i == 0 ? true : false)
					});
				}
			} else {
				subtitles.push({
					"kind":"captions",
					"file":params.sub,
					"label":subtitle,
					"language":null,
					"default":true
				});
			}
		}
	}

	if(p2p) {
		if(Hls.isSupported() && p2pml.hlsjs.Engine.isSupported()) {
			const config = {
				segments:{
					/*
					forwardSegmentCount:50,
					*/
					forwardSegmentCount: 4000,
					swarmId: jData.videoSource
				},
				loader: {
					trackerAnnounce: (videoSettings.p2pTrackers.length ? videoSettings.p2pTrackers : ["wss://tracker.novage.com.ua/","wss://tracker.openwebtorrent.com/"]),
					rtcConfig: {
						iceServers: [
							{ urls: "stun:stun.l.google.com:19302" },
							{ urls: "stun:global.stun.twilio.com:3478?transport=udp" }
						]
					},
					/*
					cachedSegmentExpiration:432000,
					cachedSegmentsCount:15000,
					*/
					/*
					cachedSegmentExpiration:86400000,
					cachedSegmentsCount:100,
					*/

					cachedSegmentExpiration: 86400000,
					cachedSegmentsCount: 1000,
					requiredSegmentsPriority: 3,
					httpDownloadMaxPriority: 9,
					httpDownloadProbability: 0.06,
					httpDownloadProbabilityInterval: 1000,
					httpDownloadProbabilitySkipIfNoPeers: true,
					p2pDownloadMaxPriority: 50,
					httpFailedSegmentTimeout: 1000,
					simultaneousP2PDownloads: 50,
					simultaneousHttpDownloads: 2,
					httpDownloadInitialTimeout: 60000,
					httpDownloadInitialTimeoutPerSegment: 3000,
					httpUseRanges: true,
				}
			};
			var engine = new p2pml.hlsjs.Engine(config);
		}
	}

	var isHls = jData.hls;
	var jwSettings = {
/*		mute: 1, */
		width: "100%",

	/*
		aspectratio: "16:8.6",
		stretching: "uniform",

		stretching: "exactfit",
	*/
		stretching: videoSettings.stretching,

		primary: "html5",
		preload: "auto",
		wmode: "opaque",

		mute:false,
		autostart: AutoStart,

		image: (jData.videoImage ? jData.videoImage : videoSettings.defaultImage),
		title:videoSettings.title,
		displaytitle:videoSettings.displaytitle,

		skin:{name:videoSettings.skin.name},
		cast: {},

		playbackRateControls: [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2],

		abouttext: "Fire Video Player",
		aboutlink: "https://firevideoplayer.com/?ref=" + window.location.hostname,

		logo: {
			file: (videoSettings.logo.active ? videoSettings.logo.file : ""),
			link: (videoSettings.logo.active ? videoSettings.logo.link : ""),
			hide: false, /**/
			position: videoSettings.logo.position,
			linktarget: "_blank"
		},
		advertising: videoSettings.advertising,
		tracks: (subtitles.length > 0 ? subtitles : videoSettings.tracks),
		captions: {
			color: "#FFFFFF",
			backgroundOpacity: 0,
			edgeStyle: "uniform", /* "raised"*/
			fontSize: videoSettings.captions.fontSize,
			fontfamily: videoSettings.captions.fontfamily
		},
		intl: {
			// https://ssl.p.jwpcdn.com/player/v/8.18.0/translations/tr.json
			"tr": {
				"audioTracks": "Dublaj Seçenekleri",
				"cast": "Ekrana Yansıt",
				"cc": "Altyazı Seçenekleri",
				"hd": "Kalite Seçenekleri",
				"playbackRates": "Oynatma Hızları",
			}
		}
		/*,
        hlsjsConfig: {
			//debug: true
			p2pConfig: {
				logLevel: true,
				live: true,
			}
		},
		*/
	};

	if(isHls) {
		jwSettings.file = jData.videoSource;
		jwSettings.type = "hls";
	} else {
		jwSettings.sources = jData.videoSources;
	}

	var	player = jwplayer("player");
	player.setup(jwSettings);

	if(p2p) {
		if(Hls.isSupported() && p2pml.hlsjs.Engine.isSupported()) {
			jwplayer_hls_provider.attach();

			p2pml.hlsjs.initJwPlayer(player, {
				/*liveSyncDurationCount: 7, */
				loader: engine.createLoaderClass()
			});

			engine.on('piece_bytes_downloaded', function(a, b, c) {
				if (c) {
					downloaded += b;
				};
				downloaded_total += b;
			});

			setInterval(function() {
				$('#StatusText')['html']('P2P: <span style="font-weight:normal; font-size:11px; font-weight: 700; top:-3px; margin-left:2px; padding: 3px 5px 3px 5px; border-radius: 5px; background:#35c2ff; color:#000; position:relative;">' + Math.round((downloaded * 100) / downloaded_total) + '%</span></b>');
			}, 1500);
		}
	}
	
	if(false) {
		if(videoSettings.jwplayer8button1 && !window.mobileAndTabletCheck()) {
			player.addButton("../player/assets/jwplayer/icons/next.svg", (player_language == "tr" ? "30 Saniye İleri" : "Forward 30 seconds"), function () {
				player.seek(player.getPosition() + 30);
			}, "forward");
			player.addButton("../player/assets/jwplayer/icons/back.svg", (player_language == "tr" ? "10 Saniye Geri" : "Backward 10 seconds"), function () {
				player.seek(player.getPosition() - 10);
			}, "backward");
		/*
			player.addButton("../player/assets/jwplayer/svg/skip-forward.svg", (player_language == "tr" ? "Atla" : "Skip Forward"), function () {
				player.seek(player.getPosition() + 90)
			}, "skip-forward");
		*/
		}
	}

	if(videoSettings.downloadFile && videoSettings.downloadType == 2 && jData.downloadLinks.length) {
		player.addButton("../player/assets/jwplayer/icons/download.svg", (player_language == "tr" ? "İndir" : "Download Video"), function () {
			var win = window.open("/download/" + ID, "_blank");
			win.focus();
		}, "download");
	}

	if(false) {
		var thanks=true;
		player.once("play", function () {
			let resumeAt = localStorage['position_' + ID];
			var cc = (player_language == "tr" ? "Devam" : "Confirm");
			var cs = (player_language == "tr" ? "İptal" : "Cancel");
			if (resumeAt && Math.floor(resumeAt) > 3) {
				player.pause();
				$("body").prepend(`
				<div class="modals">
					<div class="modal">
						<div class="modal-body">
							<span>Continue watching at ${SecondsConvert(resumeAt)}</span>
						</div>
						<div class="modal-footer">
							<button class="cs">${cc}</button>
							<button class="cc">${cs}</button>
						</div>
					</div>
				</div>`);
				$(".cs").click(() => {
					player.seek(resumeAt).play();
					$(".modals").remove();
				});
				$(".cc").click(() => {
					player.seek(0).play();
					$(".modals").remove();
				});
			}
		});
	}

	player.on('play', function() {
		if(isHls) {
			if(false) {
				if(!Played) {
					qualityList = player.getQualityLevels();
					if(qualityList.length) {
						for(var i=0;i<qualityList.length;i++) {
							if(qualityList[i]['label'] == "1080p") {
								player.setCurrentQuality(i);
								break;
							}
						}
					}
				}
			}
			if(currentAudioTrack > -1 && player.getCurrentAudioTrack() != currentAudioTrack) {
				player.setCurrentAudioTrack(currentAudioTrack);
			}
		}
		Played = true;
		if(videoSettings.jwplayer8quality) {
			if(!Once) {
				Once = true;
				if(!p2p) {
					UpdateQualityText();
				}
			}
		}
		if(videoSettings.rememberPosition) {
			if (Seeking && localStorage['position_' + ID] > 3) {
				player.seek(localStorage['position_' + ID] - 3);
			}
		}
		if($('#adStop')) { $('#adStop').hide(); }
		if(progress) {
			progress.reset();
		}
	});

	player.on('pause', function() {
		if($('#adStop')) { $('#adStop').show(); }
		if(progress) {
			progress.reset();
		}
	});

	player.on('audioTracks', function(e) {
		if(!isHls)
			return;

		audioTracks = player.getAudioTracks();
		if(audioTracks.length > 1 && !bLoaded) {
			if(player.getCurrentAudioTrack() == -1) {
				for(let i in defaultAudio) {
					for(var index=0;index<audioTracks.length;index++) {
						if(audioTracks[index].language == defaultAudio[i]) {
							currentAudioTrack = audioTracks[index].hlsjsIndex;
							break;
						}
					}
					if(currentAudioTrack > -1) {
						break;
					}
				}
			}

			if(!window.mobileAndTabletCheck()) {
				player.addButton("../player/assets/jwplayer/icons/audio2.svg", (player_language == "tr" ? "Dublaj Seçenekleri" : "Audio Tracks"), function() {
					var settingsOpen = $(".jw-controls").hasClass("jw-settings-open");
					if(settingsOpen) {
						$(".jw-settings-back").css("display", "none");
						$(".jw-settings-topbar").removeClass("jw-nested-menu-open");
						$(".jw-controls").removeClass("jw-settings-open");
						$(".jw-settings-menu").attr("aria-expanded", "false");
						$(".jw-submenu-audioTracks").attr("aria-expanded", "false");
						$(".jw-settings-submenu").each(function() {
							$(this).removeClass("jw-settings-submenu-active").attr("aria-expanded", "false");
						});
					} else {
						$(".jw-controls").addClass("jw-settings-open");
						$(".jw-settings-menu").attr("aria-expanded", "true");
						$(".jw-submenu-audioTracks").attr("aria-expanded", "true");
						$(".jw-settings-submenu-audioTracks").addClass("jw-settings-submenu-active").attr("aria-expanded", "true");
					}
				}, "audioTracks");
				$(".jw-submenu-playbackRates, .jw-submenu-captions, .jw-submenu-quality, .jw-settings-submenu-button").on("click", function(){
					$(".jw-submenu-audioTracks").attr("aria-expanded", "false");
					$(".jw-settings-submenu-audioTracks").removeClass("jw-settings-submenu-active").attr("aria-expanded", "false");
				});
			}
			bLoaded = true;
		} else if(bLoaded) {
			currentAudioTrack = audioTracks[e.currentTrack].hlsjsIndex;
		}

		currentCaptions = -1;
		captionsList = player.getCaptionsList();
		if(captionsList.length) {
			for(var language in defaultCaptions) {
				for(var index=0;index<captionsList.length;index++) {
					if((captionsList[index].language == defaultCaptions[language] || !defaultCaptions[language]) && audioTracks[e.currentTrack].language == language) {
						currentCaptions = index;
						break;
					}
				}
				if(currentCaptions >= 0) {
					player.setCurrentCaptions(currentCaptions);
					break;
				}
			}
		}
	});

	player.on('displayClick', function() {
		if(videoSettings.jwplayer8quality && !p2p) {
			if($('#QualityText').length == 0) {
				var mo = new MutationObserver(function(m) {
					if(!$('.jw-flag-user-inactive').length) {
						$('#QualityText').show();
					} else {
						$('#QualityText').hide();
					}
				});
				mo.observe(document.querySelector('.jwplayer'), {
					attributes: true
				});

				$('.jw-media').prepend(
					'<div id="QualityText" style="display:none; width:170px; height:30px; font: normal 16px arial; line-height:30px; text-align:right; color:#fff; background:#0000; position:absolute; right:20px; top:30px; z-index:5;"></div>'
				);
			}
		}

		if(p2p) {
			if ($('#StatusText')['length'] == 0) {
				var mo = new MutationObserver(function(m) {
					if(!$('.jw-flag-user-inactive').length) {
						$('#StatusText').show();
					} else {
						$('#StatusText').hide();
					}
				});
				mo.observe(document.querySelector('.jwplayer'), {
					attributes: true
				});

				$('.jw-media')['prepend']('<div id="StatusText" style="display:none; width:170px; height:30px; font: normal 16px arial; line-height:30px; text-align:right; color:#fff; background:#0000; position:absolute; right:20px; top:30px; z-index:5;"></div>');
			}
		}
	});

	if(videoSettings.jwplayer8quality && !p2p) {
		player.on('levelsChanged', function(e, a) {
			freezeCnt = 0;
			UpdateQualityText();
		});

		if(!player_loaded) {
			setInterval(function() {
				if(Played && player.getQualityLevels()[player.getCurrentQuality()]['label'] == 'Auto') {
					var label = player.getQualityLevels()[player.getVisualQuality().level.index]['label'];
						$('#QualityText').html((player_language == "tr" ? "Aktif Kalite:" : "Quality:") + ' <b>' + (player_language == "tr" ? "Otomatik" : "Auto") + ' <span style="font-weight:normal; font-size:11px; font-weight: 700; top:-3px; margin-left:2px; padding: 3px 5px 3px 5px; border-radius: 5px; background:#35c2ff; color:#000; position:relative;">' + label + '</span></b>');
				}
			}, 1000);
		}
	}

	player.on('buffer', function(e) {
		if(false && !window.mobileAndTabletCheck()) {
			var thanks=true;
			if(!progress) {
				$(".jw-svg-icon-buffer").parent().append('<span id="number" class="jw-icon" style="position: absolute; font-size: 15px; color: white; z-index: 1; text-shadow: rgb(0, 0, 0) 0px 0px 5px;">0%</span>' );
				progress = new CountUp("number", 0, 99, 0, 9, options);
			}
			if(progress) {
				progress.start();
			}
		}

		if(false) {
			if(e.reason == 'stalled') {
				freezeCnt++;
				if(Played && freezeCnt > 10) {
					var curQualityIndex = player.getCurrentQuality();
					if(curQualityIndex > 0) {
						player.setCurrentQuality(0);
					}
				}
			}
		}
	});

	player.on('seek', function() {
		if(videoSettings.rememberPosition) { Seeking = false; }
	});
	player.on('error', function() {
		if(tryCount < 2) {
			tryCount++;
			FirePlayer_jwplayer8(ID, videoSettings, true, jData, Seeking);
		}
	});

	player.on('time', function() {
		if(videoSettings.rememberPosition) { localStorage.setItem('position_' + ID, player.getPosition()); }

		if(videoSettings.fields.intro && bIntro == 0) {
			const introTimeInSeconds = hmsToSeconds(videoSettings.fields.intro);
			if(player.getPosition() <= introTimeInSeconds) {
				bIntro = 1;
				$('.jw-wrapper').prepend(
					'<div class="jw-reset jw-intro"><div class="boxRight"><a href="javascript:;" id="skip-intro" class="iBtn iBtn-outline">Skip Intro</a></div></div>'
				);
				$("#skip-intro").off("click").click(function() {
					player.seek(introTimeInSeconds);
					$(".jw-intro").remove();
					bIntro = 2;
				});
			}
		} else if(bIntro == 1) {
			if(player.getPosition() > hmsToSeconds(videoSettings.fields.intro)) {
				bIntro = 3;
				$(".jw-intro").remove();
			}
		}

		if(videoSettings.fields.outro && bOutro == 0) {
			const introTimeInSeconds = hmsToSeconds(videoSettings.fields.outro);
			if(player.getPosition() >= introTimeInSeconds) {
				bOutro = 1;
				$('.jw-wrapper').prepend(
					'<div class="jw-reset jw-outro"><div class="boxRight"><a href="javascript:;" id="skip-outro" class="iBtn iBtn-outline">Skip Outro</a></div></div>'
				);
				$("#skip-outro").off("click").click(function() {
					player.seek(player.getDuration());
					$(".jw-outro").remove();
					bOutro = 2;
				});
			}
		} else if(bOutro == 1) {
			if(player.getPosition() < hmsToSeconds(videoSettings.fields.outro)) {
				bOutro = 3;
				$(".jw-outro").remove();
			}
		}
	});
	player.on('complete', function() {
		if(videoSettings.rememberPosition) { delete localStorage['position_' + ID]; }
	});
	player.on('ready', function() {
		$('.loader').remove();

		if(videoSettings.tracks.length && videoSettings.SubtitleManager) {
			SubtitleManager.setup();
		}

		if(true) {
			/* play/stop */
			const playerContainer = "#playerbase";
			const rewindButton = document.querySelector('.jw-display-icon-rewind');
			const rewDiv = $(rewindButton).find(".jw-icon-rewind");
			const cloneIcon2 = rewDiv.clone();
			cloneIcon2.find("path").remove();
			cloneIcon2.find("svg").addClass("jw-svg-icon-custom-forward").attr("style", "width: 39px !important; height: 39px !important;");
			$(cloneIcon2).addClass("forward")/*.css("transform", "scaleX(-1)")*/.attr("aria-label", (player_language == "tr" ? "10 Saniye İleri" : "Forward 10 Seconds")).prependTo($(playerContainer).find('.jw-display-icon-next'));
			$(playerContainer).find('.jw-display-icon-next .jw-icon-next').hide();
			$(playerContainer).find('.jw-display-controls .jw-display-icon-next').css('visibility', 'visible');

			/* controlbar */
			const rewindContainer = document.querySelector('.jw-button-container');
			const rewindIcon = $(rewindContainer).find(".jw-icon-rewind");
			const cloneIcon = rewindIcon.clone();
			cloneIcon.find("path").remove();
			cloneIcon.find("svg").addClass("jw-svg-icon-custom-forward").attr("style", "width: 18px !important; height: 18px !important;");
			cloneIcon.addClass("forward")/*.css("transform", "scaleX(-1)")*/.attr("aria-label", (player_language == "tr" ? "10 Saniye İleri" : "Forward 10 Seconds")).insertAfter(rewindIcon);

			$(".jw-icon-rewind.forward").on('click', function() {
				player.seek((player.getPosition() + 10));
			});

			/* backward play/stop */
			rewDiv.find("path").remove();
			rewDiv.find("svg").addClass("jw-svg-icon-custom-backward").attr("style", "width: 40px !important; height: 40px !important;");
			/* backward controlbar */
			rewindIcon.find("path").remove();
			rewindIcon.find("svg").addClass("jw-svg-icon-custom-backward").attr("style", "width: 19px !important; height: 19px !important;");
		}

		$('#playerbase').show();

		if(videoSettings.popactive) {
			var popcounter = parseInt(getCookie("popcounter"));
			if(isNaN(popcounter)) {
				popcounter = 0;
			}
			if(popcounter < videoSettings.poplimit || videoSettings.poplimit == 0) {
				$("body").append('<div class="pppx"></div>');
				waitFor('.pppx', function(popelement) {
					$(".pppx").click(function() {
						window.open(videoSettings.popurl, videoSettings.popurl);

						if(videoSettings.poplimit > 0) {
							setCookie("popcounter", popcounter + 1, 1);
						}

						$(this).remove();
						player.play();
					});
				});
			}
		}

		if(false) {
			captionsList = player.getCaptionsList();
			if(captionsList.length) {
				currentCaptions = 1;
				player.setCurrentCaptions(currentCaptions);
			}
		}

		waitFor('.jwplayer', function(jwObject) {
			var list = [
				"/player/assets/normalize.css",
				"/player/assets/style.css",
			];
			loadAssets(list);
			addCssToDocument(".jw-state-idle .jw-title { text-align: center; }");
			/*
			addCssToDocument(".jw-breakpoint-4 .jw-settings-menu, .jw-breakpoint-3 .jw-settings-menu { min-width: 200px; }");
			addCssToDocument(".jw-settings-submenu { padding: 10px 20px 10px 10px; }");
			*/
		});
	});
}

function FirePlayer_playerjs(ID, videoSettings, AutoStart, jData, Seeking) {
	var player = new Playerjs({
        id: "playerjs",
        file: jData.videoSource,
		type: "hls",
        subtitle: playerjsSubtitle,
		default_subtitle: playerjsDefaultSubtitle,
        poster: (jData.videoImage ? jData.videoImage : videoSettings.defaultImage),
	});
	$('.loader').remove();
	$('#playerjs').show();
}

function UpdateQualityText() {
	var label = jwplayer().getQualityLevels()[jwplayer().getCurrentQuality()]['label'];
	if(label.length > 1) {
		$('#QualityText').show().html((player_language == "tr" ? "Aktif Kalite:" : "Quality:") + ' <b>' + label + '</b>');
	}
}
