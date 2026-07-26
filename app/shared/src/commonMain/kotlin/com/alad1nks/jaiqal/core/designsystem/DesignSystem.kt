package com.alad1nks.jaiqal.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jaiqal.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

object Spacing { val xs=4.dp; val sm=8.dp; val md=16.dp; val lg=24.dp; val xl=32.dp }
private val Light=lightColorScheme(primary=Color(0xFF246B45),secondary=Color(0xFF53634F),background=Color(0xFFF7FAF6),error=Color(0xFFBA1A1A))
private val Dark=darkColorScheme(primary=Color(0xFF8ED5A8),secondary=Color(0xFFBBCBB7),background=Color(0xFF101510))
@Composable fun JaiqalTheme(dark:Boolean=false,content: @Composable () -> Unit)=MaterialTheme(colorScheme=if(dark) Dark else Light,typography=Typography(titleLarge=MaterialTheme.typography.titleLarge.copy(fontSize=28.sp)),shapes=Shapes(medium=RoundedCornerShape(18.dp)),content=content)
@Composable fun PrimaryButton(text:String,enabled:Boolean=true,onClick:()->Unit)=Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(text)}
@Composable fun OfflineBanner()=Text(stringResource(Res.string.offline),modifier=Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(Spacing.sm),color=MaterialTheme.colorScheme.onTertiaryContainer)
@Composable fun LoadingState()=Box(Modifier.fillMaxSize().padding(Spacing.xl)){CircularProgressIndicator()}
@Composable fun EmptyState(text:String,action:String?=null,onAction:()->Unit={})=Column(Modifier.fillMaxWidth().padding(Spacing.xl),verticalArrangement=Arrangement.spacedBy(Spacing.md)){Text(text,style=MaterialTheme.typography.titleMedium); action?.let{PrimaryButton(it,onClick=onAction)}}
@Composable fun ErrorState(onRetry:()->Unit)=EmptyState("Something went wrong",stringResource(Res.string.retry),onRetry)
@Composable fun MetricCard(label:String,value:String,modifier:Modifier=Modifier)=Card(modifier){Column(Modifier.padding(Spacing.md)){Text(label,style=MaterialTheme.typography.labelLarge);Text(value,style=MaterialTheme.typography.headlineSmall)}}
@Composable fun PlantCard(name:String,species:String?,soil:String?,onClick:()->Unit)=Card(onClick=onClick,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(Spacing.md),verticalArrangement=Arrangement.spacedBy(Spacing.xs)){Text(name,style=MaterialTheme.typography.titleMedium);species?.let{Text(it)};soil?.let{Text(it)}}}
