// Ponte entre as páginas e o aplicativo Android (só existe dentro do aplicativo instalado).
(function(){
  "use strict";
  var P=window.Papelaria, ativo=!!P;
  function b64ToBytes(b){var s=atob(b),n=s.length,u=new Uint8Array(n);for(var i=0;i<n;i++)u[i]=s.charCodeAt(i);return u;}
  window.PapelariaApp={
    ativo:ativo,
    // acao: 'imprimir' (abre o Epson iPrint) ou 'compartilhar' (lista de aplicativos)
    enviar:function(blob,name,acao,msg){
      if(!ativo)return;
      if(msg)msg(acao==='compartilhar'?'Abrindo a lista de aplicativos…':'Abrindo o Epson iPrint…');
      var r=new FileReader();
      r.onload=function(){
        var s=String(r.result),b64=s.slice(s.indexOf(',')+1),mime=blob.type||'application/pdf';
        try{ if(acao==='compartilhar')P.compartilhar(b64,name,mime); else P.imprimir(b64,name,mime); }
        catch(e){ if(msg)msg('Não consegui abrir o aplicativo ('+e+').'); }
      };
      r.onerror=function(){if(msg)msg('Não consegui preparar o arquivo. Tente de novo.');};
      r.readAsDataURL(blob);
    },
    pegarArquivos:function(){
      if(!ativo)return [];
      var j=[];try{j=JSON.parse(P.pegarArquivos()||'[]');}catch(e){}
      return j.map(function(o){
        var bytes=b64ToBytes(o.b64),f;
        try{f=new File([bytes],o.name,{type:o.mime});}catch(e){f=new Blob([bytes],{type:o.mime});f.name=o.name;}
        return f;
      });
    },
    pegarTexto:function(){if(!ativo)return '';try{return P.pegarTexto()||'';}catch(e){return '';}}
  };
  if(ativo)document.documentElement.className+=' app-android';
})();
