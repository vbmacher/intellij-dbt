// Run with NODE_PATH pointing to an installed Playwright package and DBT_CHROMIUM set when needed.
const {test, after, before} = require('node:test');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.DBT_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/lineage');
let browser, page, errors=[];
const fixture = {focus:'model',nodes:[{id:'source',name:'events',kind:'source_table',package:'analytics',schema:'raw'},{id:'model',name:'orders',kind:'model',package:'analytics',schema:'mart',focus:true,materialized:'incremental'},{id:'report',name:'report',kind:'model',package:'analytics'},{id:'other',name:'unrelated',kind:'seed',package:'analytics'}],edges:[{source:'source',target:'model'},{source:'model',target:'report'}],boundary:[{direction:'upstream',count:7,anchors:['source']}],direction:'LR',edgeStyle:'round-taxi',docsVisible:true,docsWidth:360};
const doc={name:'analytics.orders',kind:'model',logical:'orders',description:'<script>bad()</script>\n'+ 'Long description. '.repeat(250),source:'/project/models/orders.sql',tags:['finance'],attributes:{materialized:'incremental',relation_name:'db.mart.orders'},columns:[{name:'id',type:'bigint',description:'Identifier',declarations:['unique']}],tests:[{name:'unique',column:'id'}],rawSql:'select '+ 'wide_column, '.repeat(80)+'id',compiledSql:null,upstream:['events'],downstream:['report']};
before(async()=>{
 browser=await chromium.launch({headless:true,executablePath:process.env.DBT_CHROMIUM, args:['--no-sandbox']});
 page=await browser.newPage({viewport:{width:1400,height:850}});page.on('pageerror',e=>errors.push(e.message));
 const scripts=['cytoscape.min.js','dagre.min.js','cytoscape-dagre.min.js'].map(x=>'<script>'+fs.readFileSync(path.join(root,'vendor',x),'utf8').replaceAll('</script','<\\/script')+'</script>').join('');
 const html=fs.readFileSync(path.join(root,'index.html'),'utf8').replace('<!-- SCRIPTS -->',()=>scripts+'<script>window.events=[];window.dbtSend=x=>window.events.push(JSON.parse(x));</script>');
 await page.setContent(html); await page.evaluate(p=>window.dbtUpdate(p),fixture);
});
after(async()=>{await browser.close()});
async function nodePosition(id){return page.evaluate(id=>{const p=cy.getElementById(id).renderedPosition(),r=document.getElementById('cy').getBoundingClientRect();return{x:r.x+p.x,y:r.y+p.y}},id)}
test('bundled graph loads and cards show resource identity',async()=>{assert.deepEqual(errors,[]);assert.equal(await page.locator('.card:not(.boundary)').count(),4);assert.match(await page.locator('#cards').innerText(),/orders/)});
test('resource hover shows available details',async()=>{const p=await nodePosition('source');await page.mouse.move(p.x,p.y);assert.match(await page.locator('#tooltip').innerText(),/analytics.events/);await page.mouse.move(10,800)});
test('single click selects docs and dims unrelated resources without focusing',async()=>{const p=await nodePosition('model');await page.mouse.click(p.x,p.y);await page.waitForTimeout(230);assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='focus').length),0);assert.ok(await page.evaluate(()=>events.some(e=>e.type==='select'&&e.id==='model')));assert.equal(await page.locator('.card.dim').count(),1);await page.evaluate(d=>dbtShowDoc('model',d),doc);assert.equal(await page.locator('#doc-name').innerText(),'analytics.orders');assert.match(await page.locator('#description').innerText(),/<script>/);assert.equal(await page.locator('#description script').count(),0);if(process.env.DBT_LINEAGE_SCREENSHOT)await page.screenshot({path:process.env.DBT_LINEAGE_SCREENSHOT})});
test('tabs retain declarations, SQL whitespace, missing compiled state, and full metadata',async()=>{await page.getByRole('button',{name:'Tests',exact:true}).click();assert.match(await page.locator('#doc-content').innerText(),/Declarations only/);await page.getByRole('button',{name:'SQL',exact:true}).click();assert.match(await page.locator('#doc-content').innerText(),/Compiled SQL unavailable/);assert.ok(await page.locator('pre').evaluate(e=>e.scrollWidth>e.clientWidth));await page.getByRole('button',{name:'Metadata',exact:true}).click();assert.match(await page.locator('#doc-content').innerText(),/Downstream/);await page.getByRole('button',{name:'Expand description'}).click();assert.equal(await page.locator('#description.expanded').count(),1)});
test('stale docs never overwrite the selected resource',async()=>{await page.evaluate(d=>dbtShowDoc('some-other-resource',{...d,name:'WRONG'}),doc);assert.equal(await page.locator('#doc-name').innerText(),'analytics.orders')});
test('documentation toggles and stays hidden across refreshes and model changes',async()=>{
 const toggle=page.getByRole('button',{name:'Toggle documentation',exact:true});
 await toggle.click();assert.equal(await page.locator('#docs').isVisible(),false);
 assert.equal(await toggle.getAttribute('aria-pressed'),'false');
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='docs'&&e.value==='false')));
 await page.evaluate(p=>dbtUpdate({...p,docsVisible:false}),fixture);
 assert.equal(await page.locator('#docs').isVisible(),false);
 await toggle.click();assert.equal(await page.locator('#docs').isVisible(),true);
 await toggle.click();assert.equal(await page.locator('#docs').isVisible(),false);
 await page.evaluate(p=>dbtUpdate({...p,focus:'report',docsVisible:false}),fixture);
 assert.equal(await page.locator('#docs').isVisible(),false);
 await page.evaluate(p=>dbtUpdate(p),fixture);await page.evaluate(d=>dbtShowDoc('model',d),doc);
});
test('documentation controls stay together on the right when the window narrows',async()=>{
 try{
  for(const width of [1400,600]){
   await page.setViewportSize({width,height:850});
   const status=await page.locator('#status').boundingBox(),toggle=await page.locator('#show-docs').boundingBox(),regenerate=await page.locator('#regenerate').boundingBox();
   assert.ok(toggle.x>=status.x+status.width-1);assert.ok(regenerate.x>toggle.x);
   assert.ok(Math.abs(regenerate.x+regenerate.width-(width-10))<=1);
   for(const name of ['Clear search','Zoom in','Zoom out','Fit graph','Toggle documentation','Regenerate Docs'])
    assert.ok(await page.getByRole('button',{name,exact:true}).getAttribute('title'));
  }
 }finally{await page.setViewportSize({width:1400,height:850})}
});
test('Regenerate Docs requests generation and is disabled while a command runs',async()=>{
 const count=await page.evaluate(()=>events.filter(e=>e.type==='regenerate').length);
 await page.getByRole('button',{name:'Regenerate Docs',exact:true}).click();
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='regenerate').length),count+1);
 await page.evaluate(()=>dbtRunnerState(true,'Generating docs'));
 assert.equal(await page.locator('#regenerate').isDisabled(),true);
 await page.evaluate(()=>dbtRunnerState(false,'Docs ready'));
 assert.equal(await page.locator('#regenerate').isEnabled(),true);
});
test('hide, show, resize, zoom, fit and search controls work',async()=>{await page.getByRole('button',{name:'Close documentation'}).click();assert.equal(await page.locator('#docs').isVisible(),false);await page.getByRole('button',{name:'Toggle documentation',exact:true}).click();const handle=await page.locator('#handle').boundingBox();await page.mouse.move(handle.x+2,handle.y+100);await page.mouse.down();await page.mouse.move(handle.x-90,handle.y+100);await page.mouse.up();assert.ok((await page.locator('#docs').boundingBox()).width>400);const zoom=await page.evaluate(()=>cy.zoom());await page.locator('#zoom-in').click();assert.ok(await page.evaluate(()=>cy.zoom())>zoom);await page.locator('#fit').click();await page.locator('#search').fill('orders');assert.equal(await page.locator('.card:not(.dim):not(.boundary)').count(),1);await page.locator('#clear-search').click()});
test('viewport survives same-focus artifact and theme refresh',async()=>{await page.evaluate(()=>{cy.zoom(.75);cy.pan({x:123,y:65})});await page.evaluate(p=>dbtUpdate({...p,theme:{bg:'#fafafa',fg:'#222222',panel:'#eeeeee'}}),fixture);assert.equal(await page.evaluate(()=>cy.zoom()),.75);assert.deepEqual(await page.evaluate(()=>cy.pan()),{x:123,y:65});assert.equal(await page.locator('body').evaluate(e=>getComputedStyle(e).backgroundColor),'rgb(250, 250, 250)')});
test('mouse wheel zoom responds promptly in both directions and keeps the cursor anchored',async()=>{
 const original=await page.evaluate(()=>({zoom:cy.zoom(),pan:cy.pan()}));
 try{
  await page.evaluate(()=>{cy.zoom(.75);cy.pan({x:123,y:65})});
  const box=await page.locator('#cy').boundingBox(),cursor={x:Math.floor(box.width*.4),y:Math.floor(box.height*.5)};
  await page.mouse.move(box.x+cursor.x,box.y+cursor.y);
  const anchor=()=>page.evaluate(p=>({x:(p.x-cy.pan().x)/cy.zoom(),y:(p.y-cy.pan().y)/cy.zoom()}),cursor);
  const before=await anchor();
  async function scroll(delta){for(let i=0;i<8;i++){const zoom=await page.evaluate(()=>cy.zoom());await page.mouse.wheel(0,delta);await page.waitForFunction(z=>cy.zoom()!==z,zoom)}}
  await scroll(-120);const zoomed=await page.evaluate(()=>cy.zoom());
  assert.ok(zoomed>=.75*1.2,'Eight wheel notches should increase magnification by at least 20%');
  await scroll(120);assert.ok(await page.evaluate(()=>cy.zoom())<=zoomed*.9,'Scrolling back should reduce magnification promptly');
  const after=await anchor();assert.ok(Math.abs(after.x-before.x)<1e-6&&Math.abs(after.y-before.y)<1e-6);
 }finally{await page.evaluate(v=>{cy.zoom(v.zoom);cy.pan(v.pan)},original)}
});
test('boundary click expands without refocusing; double click focuses',async()=>{await page.locator('#fit').click();const p=await nodePosition('boundary:upstream');await page.mouse.click(p.x,p.y);await page.waitForTimeout(230);assert.ok(await page.evaluate(()=>events.some(e=>e.type==='expand'&&e.id==='upstream')));const n=await nodePosition('report');await page.mouse.dblclick(n.x,n.y,{delay:80});await page.waitForTimeout(230);assert.ok(await page.evaluate(()=>events.some(e=>e.type==='focus'&&e.id==='report')))});
test('drag moves a card without navigation and background clears dimming',async()=>{const p=await nodePosition('model');const before=await page.evaluate(()=>events.filter(e=>e.type==='focus').length);await page.mouse.move(p.x,p.y);await page.mouse.down();await page.mouse.move(p.x+45,p.y+70,{steps:8});await page.mouse.up();await page.waitForTimeout(230);assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='focus').length),before);await page.mouse.click(10,800);assert.equal(await page.locator('.card.dim').count(),0)});
test('every direction and edge style renders without script errors',async()=>{for(const direction of ['LR','TB','RL','BT'])for(const edgeStyle of ['bezier','taxi','round-taxi','segments','straight','unbundled-bezier','haystack'])await page.evaluate(p=>dbtUpdate(p),{...fixture,direction,edgeStyle});assert.deepEqual(errors,[])});
