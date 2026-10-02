(function(){
  // Native bridge: real Android app provides window.PragonNative; browser preview mocks it.
  const N = window.PragonNative || {
    remote:(a,v,dx,dy)=>{ if(a!=='mouse_move') toast('PC ← '+a+(v?': '+v:'')); },
    typeText:(s)=>toast('PC types: '+s),
    openSettings:()=>window.open('pragon_settings.html','_blank')
  };
  const $=id=>document.getElementById(id);
  const GEAR='<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>';
  const FOLDER='<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>';
  // swap: Files & Devices goes to the bottom bar, Settings takes its place in the header
  const hist=document.querySelector('.header [title="Files & Devices"]');
  if(hist){const gear=document.createElement('button');gear.className='hbtn';gear.id='pmSet';gear.title='Settings';gear.innerHTML=GEAR;
    hist.parentNode.insertBefore(gear,hist);hist.className='pm-b';hist.id='pmFiles';hist.innerHTML=FOLDER;}
  const ft=document.querySelector('.footer'); ['pmType','pmRemote','pmFiles'].forEach(i=>{const e=$(i);if(e)ft.appendChild(e);}); const br=document.querySelector('.pm-br'); if(br) ft.appendChild(br);
  let tt; function toast(m){const e=$('pmToast');e.textContent=m;e.classList.add('show');clearTimeout(tt);tt=setTimeout(()=>e.classList.remove('show'),1500);}
  // type-to-PC toggle: while ON, Send/Enter types on the PC instead of sending a chat command
  let typeOn=false; const inp=document.getElementById('inp'), ph=inp.placeholder;
  $('pmType').onclick=()=>{typeOn=!typeOn;$('pmType').classList.toggle('on',typeOn);inp.placeholder=typeOn?'Typing on PC — not sent to Pragon':ph;toast(typeOn?'Type-on-PC: ON':'Type-on-PC: OFF');};
  function pcType(){const v=inp.value;if(!v)return;N.typeText(v);inp.value='';}
  document.addEventListener('click',e=>{if(typeOn&&e.target.closest('.send')){e.preventDefault();e.stopImmediatePropagation();pcType();}},true);
  document.addEventListener('keydown',e=>{if(typeOn&&e.target===inp&&e.key==='Enter'){e.preventDefault();e.stopImmediatePropagation();pcType();}},true);
  // Remote sheet
  const sh=$('pmSheet'),ov=$('pmOv');
  const open=o=>{sh.classList.toggle('open',o);ov.classList.toggle('open',o);};
  $('pmRemote').onclick=()=>open(true);$('pmClose').onclick=()=>open(false);ov.onclick=()=>open(false);
  $('pmSet').onclick=()=>N.openSettings();
  sh.querySelectorAll('[data-fn]').forEach(k=>k.addEventListener('click',()=>{open(false);const f=window[k.dataset.fn];if(f)f();}));
  sh.querySelectorAll('.pm-btn[data-a]').forEach(k=>k.addEventListener('click',()=>N.remote(k.dataset.a,null,0,0)));
  sh.querySelectorAll('.pm-k[data-a]').forEach(k=>{
    const a=k.dataset.a,rep=/^(volume|brightness|scroll)/.test(a);let iv;
    const go=()=>N.remote(a,null,0,0);
    k.addEventListener('pointerdown',()=>{go();if(rep)iv=setInterval(go,180);});
    ['pointerup','pointerleave','pointercancel'].forEach(ev=>k.addEventListener(ev,()=>clearInterval(iv)));
  });
  const tg=()=>{const v=$('pmTxt').value;if(v){N.typeText(v);$('pmTxt').value='';}};
  $('pmTxtGo').onclick=tg;$('pmTxt').addEventListener('keydown',e=>{if(e.key==='Enter')tg();});
  // Joystick: push = move, distance from centre = speed
  const pad=$('pmPad'),kn=$('pmKnob'),R=47;let act=false,vx=0,vy=0,iv2;
  function mv(e){const r=pad.getBoundingClientRect();let x=e.clientX-r.left-r.width/2,y=e.clientY-r.top-r.height/2;const d=Math.hypot(x,y);if(d>R){x*=R/d;y*=R/d;}
    kn.style.transform='translate('+x+'px,'+y+'px)';vx=x/R;vy=y/R;}
  pad.addEventListener('pointerdown',e=>{act=true;pad.setPointerCapture(e.pointerId);mv(e);iv2=setInterval(()=>{const s=v=>Math.round(v*Math.abs(v)*22);if(vx||vy)N.remote('mouse_move',null,s(vx),s(vy));},33);});
  pad.addEventListener('pointermove',e=>{if(act)mv(e);});
  const end=()=>{act=false;vx=vy=0;clearInterval(iv2);kn.style.transform='';};
  pad.addEventListener('pointerup',end);pad.addEventListener('pointercancel',end);

  // ---- native-app-only overrides (PhoneView's page is plain http, so the browser mic/camera APIs are blocked in a WebView) ----
  if(window.PragonNative&&PragonNative.startVoice){
    // Mic = Google speech dialog (same as Standalone). Spoken text goes into the box and is sent.
    window.tgm=function(){PragonNative.startVoice();};
    window.__pmVoice=function(text){const i=document.getElementById('inp');if(!i||!text)return;i.value=text;if(typeof window.snd==='function')window.snd();};
    window.openCamera=function(){const i=document.createElement('input');i.type='file';i.accept='image/*';i.setAttribute('capture','environment');
      i.onchange=function(){if(!i.files.length)return;const f=document.getElementById('finp');try{const dt=new DataTransfer();for(const x of i.files)dt.items.add(x);f.files=dt.files;f.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){toast('Could not send photo');}};i.click();};
  }
})();